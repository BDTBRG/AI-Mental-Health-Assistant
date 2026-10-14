"""
RAG 服务 —— 管理 FAISS 向量索引的完整生命周期。

核心职责：
1. 文本分块：滑动窗口（512 字符 / 128 字符重叠）
2. 向量化：Sentence-Transformers (paraphrase-multilingual-MiniLM-L12-v2, 384 维）
3. 索引管理：FAISS IndexIDMap(IndexFlatIP)，支持按文章 ID 增删
4. 持久化：写入磁盘 / 启动时加载
5. 语义检索：Top-K 相似片段查询
"""

import os
import json
import logging
import math
import re
import hashlib
from typing import List, Dict, Optional
from dataclasses import dataclass

import numpy as np
import faiss
from sentence_transformers import SentenceTransformer
import config

from services.qdrant_rag_store import QdrantRAGStore

logger = logging.getLogger(__name__)

# 数据目录：存放 FAISS 索引文件和文档元数据
DATA_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "data")
INDEX_PATH = os.path.join(DATA_DIR, "faiss.index")
META_PATH = os.path.join(DATA_DIR, "chunks_meta.json")


@dataclass
class TextChunk:
    """文本分块"""
    chunk_id: int          # 分块唯一 ID
    article_id: int        # 来源文章 ID
    article_title: str     # 来源文章标题
    content: str           # 分块文本内容
    char_start: int        # 在原文章中的起始字符位置
    char_end: int          # 在原文章中的结束字符位置


class RAGService:
    """RAG 服务：封装 FAISS 索引的创建、加载、检索和更新"""

    # 分块参数
    CHUNK_SIZE = 512       # 每个块最多 512 个字符
    CHUNK_OVERLAP = 128    # 相邻块之间重叠 128 个字符（25%）

    # 检索参数
    DEFAULT_TOP_K = 3
    MIN_SEMANTIC_SCORE = 0.32
    LEXICAL_CANDIDATE_FACTOR = 4

    def __init__(
        self,
        model_name: str = "paraphrase-multilingual-MiniLM-L12-v2",
        backend: Optional[str] = None,
        qdrant_store: Optional[QdrantRAGStore] = None,
    ):
        self.model_name = model_name
        self.backend = (backend or config.RAG_BACKEND).strip().lower()
        if self.backend not in {"faiss", "qdrant"}:
            raise ValueError("RAG backend must be 'faiss' or 'qdrant'")
        self.qdrant_store = qdrant_store
        if self.backend == "qdrant" and self.qdrant_store is None:
            self.qdrant_store = QdrantRAGStore(
                collection_alias=config.RAG_QDRANT_COLLECTION_ALIAS,
                url=config.RAG_QDRANT_URL,
                api_key=config.RAG_QDRANT_API_KEY,
                redis_url=config.RAG_WRITE_LOCK_REDIS_URL,
                lock_name=config.RAG_WRITE_LOCK_NAME,
                lock_timeout_seconds=config.RAG_WRITE_LOCK_TTL_SECONDS,
                lock_wait_seconds=config.RAG_WRITE_LOCK_WAIT_SECONDS,
            )
        self.model: Optional[SentenceTransformer] = None
        self.index: Optional[faiss.Index] = None
        self.chunks: List[TextChunk] = []
        self._next_chunk_id = 0
        self.index_version = "unbuilt"
        self._qdrant_collection_name: Optional[str] = None

        if self.backend == "faiss":
            os.makedirs(DATA_DIR, exist_ok=True)

    # ========== 模型懒加载（避免启动时卡住） ==========

    def _ensure_model(self):
        if self.model is None:
            logger.info(f"加载 Sentence-Transformers 模型: {self.model_name}")
            self.model = SentenceTransformer(self.model_name)
            logger.info("模型加载完成")

    def _compute_index_version(self, chunks: Optional[List[TextChunk]] = None) -> str:
        digest = hashlib.sha256()
        for chunk in self.chunks if chunks is None else chunks:
            digest.update(str(chunk.article_id).encode("utf-8"))
            digest.update(b"\0")
            digest.update(chunk.article_title.encode("utf-8"))
            digest.update(b"\0")
            digest.update(chunk.content.encode("utf-8"))
            digest.update(b"\0")
        return digest.hexdigest()[:16]

    # ========== 文本分块 ==========

    def _split_text(self, text: str, article_id: int, article_title: str) -> List[TextChunk]:
        """
        滑动窗口分块。
        窗口大小 512 字符，步长 = 512 - 128 = 384 字符（即重叠 128）。
        """
        chunks = []
        text_len = len(text)
        if text_len == 0:
            return chunks

        start = 0
        while start < text_len:
            end = min(start + self.CHUNK_SIZE, text_len)
            chunk_text = text[start:end]
            chunk = TextChunk(
                chunk_id=self._next_chunk_id,
                article_id=article_id,
                article_title=article_title,
                content=chunk_text,
                char_start=start,
                char_end=end,
            )
            chunks.append(chunk)
            self._next_chunk_id += 1

            if end >= text_len:
                break
            # 步长 = CHUNK_SIZE - CHUNK_OVERLAP
            start += (self.CHUNK_SIZE - self.CHUNK_OVERLAP)

        return chunks

    # ========== 索引构建 ==========

    def rebuild_index(self, articles: List[Dict]) -> int:
        """
        全量重建 FAISS 索引。

        参数 articles: [{"id": 1, "title": "文章标题", "content": "文章正文"}, ...]
        返回：索引中的总块数
        """
        self._ensure_model()

        # 1. 分块
        all_chunks: List[TextChunk] = []
        self._next_chunk_id = 0
        for article in articles:
            chunks = self._split_text(
                text=article.get("content", ""),
                article_id=article.get("id", 0),
                article_title=article.get("title", ""),
            )
            all_chunks.extend(chunks)

        logger.info(f"分块完成：{len(articles)} 篇文章 → {len(all_chunks)} 个文本块")

        if self.backend == "qdrant":
            texts = [chunk.content for chunk in all_chunks]
            if texts:
                embeddings = np.asarray(
                    self.model.encode(texts, show_progress_bar=True, normalize_embeddings=True)
                ).astype("float32")
                vector_size = int(embeddings.shape[1])
                vectors = embeddings.tolist()
            else:
                dimension = getattr(self.model, "get_sentence_embedding_dimension", None)
                vector_size = int(dimension()) if callable(dimension) else 384
                vectors = []

            stats = self.qdrant_store.rebuild(
                [self._chunk_record(chunk) for chunk in all_chunks],
                vectors,
                vector_size=vector_size,
            )
            self._qdrant_collection_name = self.qdrant_store.active_collection_name
            self._apply_qdrant_snapshot(
                self.qdrant_store.load_chunks(),
                stats.get("index_version", "unbuilt"),
            )
            return int(stats["total_vectors"])

        if not all_chunks:
            logger.warning("没有可索引的内容，创建空索引")
            self.chunks = []
            self.index = None
            self.index_version = self._compute_index_version()
            if os.path.exists(INDEX_PATH):
                os.remove(INDEX_PATH)
            self._save()
            return 0

        # 2. 向量化
        texts = [c.content for c in all_chunks]
        embeddings = self.model.encode(texts, show_progress_bar=True, normalize_embeddings=True)
        embeddings = np.array(embeddings).astype('float32')

        # 3. 创建 FAISS 索引
        # IndexIDMap 包装 IndexFlatIP，支持自定义 ID 和按 ID 删除
        dim = embeddings.shape[1]  # 384
        base_index = faiss.IndexFlatIP(dim)  # 内积相似度（归一化后等价于余弦相似度）
        self.index = faiss.IndexIDMap(base_index)

        chunk_ids = np.array([c.chunk_id for c in all_chunks], dtype='int64')
        self.index.add_with_ids(embeddings, chunk_ids)
        self.chunks = all_chunks
        self.index_version = self._compute_index_version()

        # 4. 持久化到磁盘
        self._save()

        logger.info(f"索引构建完成：{self.index.ntotal} 个向量, 维度 {dim}")
        return self.index.ntotal

    # ========== 增量操作 ==========

    def add_article(self, article_id: int, title: str, content: str):
        """新增单篇文章到索引"""
        if self.backend == "qdrant":
            self._sync_qdrant_article(article_id, title, content)
            return
        if self.index is None:
            self.rebuild_index([{"id": article_id, "title": title, "content": content}])
            return

        self._ensure_model()
        chunks = self._split_text(content, article_id, title)
        if not chunks:
            return

        texts = [c.content for c in chunks]
        embeddings = self.model.encode(texts, normalize_embeddings=True)
        embeddings = np.array(embeddings).astype('float32')
        chunk_ids = np.array([c.chunk_id for c in chunks], dtype='int64')

        self.index.add_with_ids(embeddings, chunk_ids)
        self.chunks.extend(chunks)
        self.index_version = self._compute_index_version()
        self._save()
        logger.info(f"文章 {article_id} 添加完成：+{len(chunks)} 块，索引总量 {self.index.ntotal}")

    def remove_article(self, article_id: int):
        """按文章 ID 删除所有相关分块"""
        if self.backend == "qdrant":
            stats = self.qdrant_store.delete_article(article_id)
            self._qdrant_collection_name = self.qdrant_store.active_collection_name
            self._apply_qdrant_snapshot(
                self.qdrant_store.load_chunks(),
                stats.get("index_version", "unbuilt"),
            )
            return
        if self.index is None:
            return

        # 找到属于该文章的所有 chunk_id
        ids_to_remove = [
            c.chunk_id for c in self.chunks if c.article_id == article_id
        ]
        if not ids_to_remove:
            return

        id_array = np.array(ids_to_remove, dtype='int64')
        candidate_index = faiss.clone_index(self.index)
        candidate_index.remove_ids(id_array)
        candidate_chunks = [c for c in self.chunks if c.article_id != article_id]
        candidate_version = self._compute_index_version(candidate_chunks)

        # Persist the candidate before publishing it in memory. If persistence fails, a retry
        # in this process still sees the original chunks and can retry the same deletion.
        self._save_state(candidate_index, candidate_chunks, candidate_version, self._next_chunk_id)
        self.index = candidate_index
        self.chunks = candidate_chunks
        self.index_version = candidate_version
        logger.info(f"文章 {article_id} 删除完成：-{len(ids_to_remove)} 块，索引总量 {self.index.ntotal}")

    def update_article(self, article_id: int, title: str, content: str):
        """更新文章：先删旧块，再加新块"""
        if self.backend == "qdrant":
            self._sync_qdrant_article(article_id, title, content)
            return
        self.remove_article(article_id)
        self.add_article(article_id, title, content)

    @staticmethod
    def _chunk_record(chunk: TextChunk) -> Dict:
        return {
            "chunk_id": chunk.chunk_id,
            "article_id": chunk.article_id,
            "article_title": chunk.article_title,
            "content": chunk.content,
            "char_start": chunk.char_start,
            "char_end": chunk.char_end,
        }

    @staticmethod
    def _chunk_from_record(record: Dict) -> TextChunk:
        return TextChunk(
            chunk_id=int(record["chunk_id"]),
            article_id=int(record["article_id"]),
            article_title=str(record["article_title"]),
            content=str(record["content"]),
            char_start=int(record["char_start"]),
            char_end=int(record["char_end"]),
        )

    def _apply_qdrant_snapshot(self, records: List[Dict], index_version: str):
        self.chunks = [
            self._chunk_from_record(record["payload"])
            for record in records
            if record.get("payload")
        ]
        self.index = None
        self._next_chunk_id = 0
        self.index_version = index_version

    def _sync_qdrant_article(self, article_id: int, title: str, content: str):
        self._ensure_model()
        chunks = self._split_text(content or "", int(article_id), title or "")
        texts = [chunk.content for chunk in chunks]
        if texts:
            embeddings = np.asarray(
                self.model.encode(texts, normalize_embeddings=True)
            ).astype("float32")
            vector_size = int(embeddings.shape[1])
            vectors = embeddings.tolist()
        else:
            vectors = []
            vector_size = None
        stats = self.qdrant_store.sync_article(
            [self._chunk_record(chunk) for chunk in chunks],
            vectors,
            article_id=int(article_id),
            vector_size=vector_size,
        )
        self._qdrant_collection_name = self.qdrant_store.active_collection_name
        self._apply_qdrant_snapshot(
            self.qdrant_store.load_chunks(),
            stats.get("index_version", "unbuilt"),
        )

    # ========== 语义检索 ==========

    @staticmethod
    def _tokenize(text: str) -> List[str]:
        """Tokenize English words and Chinese character bigrams without a runtime tokenizer dependency."""
        tokens: List[str] = []
        for segment in re.findall(r"[a-zA-Z0-9]+|[\u4e00-\u9fff]+", text.lower()):
            if re.fullmatch(r"[a-zA-Z0-9]+", segment):
                tokens.append(segment)
            elif len(segment) == 1:
                tokens.append(segment)
            else:
                tokens.extend(segment[index:index + 2] for index in range(len(segment) - 1))
        return tokens

    def _lexical_scores(self, query: str, chunks: Optional[List[TextChunk]] = None) -> Dict[int, float]:
        """Return an IDF-weighted query-term coverage score for each chunk."""
        query_terms = set(self._tokenize(query))
        source_chunks = self.chunks if chunks is None else chunks
        if not query_terms or not source_chunks:
            return {}

        document_terms = {
            chunk.chunk_id: self._tokenize(chunk.article_title + " " + chunk.content)
            for chunk in source_chunks
        }
        document_frequency: Dict[str, int] = {}
        for terms in document_terms.values():
            for term in set(terms):
                document_frequency[term] = document_frequency.get(term, 0) + 1

        document_count = len(document_terms)
        term_weights = {
            term: math.log(1 + (document_count - document_frequency.get(term, 0) + 0.5)
                           / (document_frequency.get(term, 0) + 0.5))
            for term in query_terms
        }
        total_weight = sum(term_weights.values()) or 1.0
        scores: Dict[int, float] = {}
        for chunk_id, terms in document_terms.items():
            frequencies = {}
            for term in terms:
                frequencies[term] = frequencies.get(term, 0) + 1
            matched_weight = 0.0
            for term, weight in term_weights.items():
                frequency = frequencies.get(term, 0)
                if frequency:
                    # Saturating TF prevents repeated boilerplate from dominating ranking.
                    matched_weight += weight * (frequency * 2.2) / (frequency + 1.2)
            scores[chunk_id] = min(1.0, matched_weight / total_weight)
        return scores

    def search(self, query: str, top_k: int = DEFAULT_TOP_K) -> List[Dict]:
        """
        混合检索与轻量重排：FAISS 召回候选，再融合稠密相似度和 IDF 词项覆盖率。
        返回 Top-K 相关片段，每个元素格式：
        {"title": "文章标题", "snippet": "片段文本", "score": 0.85}
        """
        if self.backend == "qdrant":
            return self._search_qdrant(query, top_k)

        if self.index is None or self.index.ntotal == 0:
            logger.warning("索引为空，返回空结果")
            return []

        self._ensure_model()

        # 查询向量化
        query_vec = self.model.encode([query], normalize_embeddings=True)
        query_vec = np.array(query_vec).astype('float32')

        # Dense recall intentionally overfetches so lexical reranking can change the order.
        candidate_limit = min(max(top_k, top_k * self.LEXICAL_CANDIDATE_FACTOR), self.index.ntotal)
        distances, indices = self.index.search(query_vec, candidate_limit)
        chunks_by_id = {chunk.chunk_id: chunk for chunk in self.chunks}
        semantic_scores: Dict[int, float] = {}
        for dist, idx in zip(distances[0], indices[0]):
            if idx < 0:
                continue
            if int(idx) not in chunks_by_id:
                logger.warning("FAISS returned chunk ID %s without metadata", idx)
                continue
            semantic_scores[int(idx)] = max(0.0, min(1.0, float(dist)))

        lexical_scores = self._lexical_scores(query)
        lexical_candidates = sorted(lexical_scores, key=lexical_scores.get, reverse=True)[:candidate_limit]
        candidate_ids = set(semantic_scores) | set(lexical_candidates)
        results = []
        for chunk_id in candidate_ids:
            chunk = chunks_by_id.get(chunk_id)
            if chunk is None:
                continue
            semantic_score = semantic_scores.get(chunk_id, 0.0)
            lexical_score = lexical_scores.get(chunk_id, 0.0)
            # Refuse weak matches; an exact lexical hit can still rescue a domain term
            # that the embedding model ranks poorly.
            if semantic_score < self.MIN_SEMANTIC_SCORE and lexical_score < 0.25:
                continue
            confidence = 0.65 * semantic_score + 0.35 * lexical_score
            results.append({
                "chunkId": chunk.chunk_id,
                "articleId": chunk.article_id,
                "title": chunk.article_title,
                "snippet": chunk.content,
                "score": round(confidence, 4),
                "semanticScore": round(semantic_score, 4),
                "lexicalScore": round(lexical_score, 4),
            })

        return sorted(results, key=lambda item: item["score"], reverse=True)[:top_k]

    def _search_qdrant(self, query: str, top_k: int) -> List[Dict]:
        if top_k <= 0:
            return []
        metadata = self.refresh_shared_index_state()
        total_chunks = int(metadata.get("total_chunks", 0))
        if metadata.get("collection_name") is None or total_chunks == 0:
            return []

        self._ensure_model()
        query_vec = np.asarray(
            self.model.encode([query], normalize_embeddings=True)
        ).astype("float32")
        candidate_limit = min(
            max(top_k, top_k * self.LEXICAL_CANDIDATE_FACTOR),
            total_chunks,
        )
        points = self.qdrant_store.search(query_vec[0].tolist(), candidate_limit)
        semantic_scores: Dict[int, float] = {}
        chunks_by_id: Dict[int, TextChunk] = {
            chunk.chunk_id: chunk for chunk in self.chunks
        }
        for point in points:
            payload = point.payload or {}
            try:
                chunk = self._chunk_from_record(payload)
                score = max(0.0, min(1.0, float(point.score)))
            except (KeyError, TypeError, ValueError):
                logger.warning("Qdrant returned a chunk without valid metadata")
                continue
            chunks_by_id[chunk.chunk_id] = chunk
            semantic_scores[chunk.chunk_id] = score

        lexical_scores = self._lexical_scores(query)
        lexical_candidates = sorted(
            lexical_scores,
            key=lexical_scores.get,
            reverse=True,
        )[:candidate_limit]
        results = []
        for chunk_id in set(semantic_scores) | set(lexical_candidates):
            chunk = chunks_by_id.get(chunk_id)
            if chunk is None:
                continue
            semantic_score = semantic_scores.get(chunk_id, 0.0)
            lexical_score = lexical_scores.get(chunk_id, 0.0)
            if semantic_score < self.MIN_SEMANTIC_SCORE and lexical_score < 0.25:
                continue
            confidence = 0.65 * semantic_score + 0.35 * lexical_score
            results.append({
                "chunkId": chunk.chunk_id,
                "articleId": chunk.article_id,
                "title": chunk.article_title,
                "snippet": chunk.content,
                "score": round(confidence, 4),
                "semanticScore": round(semantic_score, 4),
                "lexicalScore": round(lexical_score, 4),
            })
        return sorted(results, key=lambda item: item["score"], reverse=True)[:top_k]

    # ========== 持久化 ==========

    def _save(self):
        """保存索引到磁盘"""
        self._save_state(self.index, self.chunks, self.index_version, self._next_chunk_id)

    def _save_state(self, index, chunks: List[TextChunk], index_version: str, next_chunk_id: int):
        """Persist an explicit snapshot without changing the currently published state."""
        if index is not None:
            faiss.write_index(index, INDEX_PATH)
            logger.info(f"FAISS 索引已保存: {INDEX_PATH} ({index.ntotal} 条)")

        # 保存分块元数据
        meta = [{
            "chunk_id": c.chunk_id,
            "article_id": c.article_id,
            "article_title": c.article_title,
            "content": c.content,
            "char_start": c.char_start,
            "char_end": c.char_end,
        } for c in chunks]
        with open(META_PATH, "w", encoding="utf-8") as f:
            json.dump({
                "next_chunk_id": next_chunk_id,
                "index_version": index_version,
                "chunks": meta,
            }, f, ensure_ascii=False, indent=2)
        logger.info(f"元数据已保存: {META_PATH} ({len(meta)} 条)")

    def load(self) -> bool:
        """加载配置的索引后端。成功返回 True，空索引或无文件返回 False。"""
        if self.backend == "qdrant":
            metadata = self.qdrant_store.get_index_metadata()
            self._qdrant_collection_name = metadata["collection_name"]
            self._apply_qdrant_snapshot(
                self.qdrant_store.load_chunks(),
                metadata["index_version"],
            )
            return int(metadata["total_chunks"]) > 0

        if not os.path.exists(INDEX_PATH) or not os.path.exists(META_PATH):
            logger.info("未找到持久化索引文件，将从空索引开始")
            return False

        try:
            self._ensure_model()

            # 加载 FAISS 索引
            self.index = faiss.read_index(INDEX_PATH)
            logger.info(f"FAISS 索引已加载: {INDEX_PATH} ({self.index.ntotal} 条)")

            # 加载分块元数据
            with open(META_PATH, "r", encoding="utf-8") as f:
                data = json.load(f)
            self._next_chunk_id = data.get("next_chunk_id", 0)
            self.chunks = [
                TextChunk(
                    chunk_id=m["chunk_id"],
                    article_id=m["article_id"],
                    article_title=m["article_title"],
                    content=m["content"],
                    char_start=m["char_start"],
                    char_end=m["char_end"],
                )
                for m in data["chunks"]
            ]
            self.index_version = data.get("index_version") or self._compute_index_version()
            if self.index.ntotal != len(self.chunks):
                raise ValueError("FAISS 向量数与分块元数据数量不一致")
            logger.info(f"元数据已加载: {META_PATH} ({len(self.chunks)} 条)")
            return True
        except Exception as e:
            logger.error(f"加载索引失败: {e}")
            self.index = None
            self.chunks = []
            return False

    def refresh_shared_index_state(self) -> Dict:
        """Refresh this replica's index metadata when another replica published an update."""
        if self.backend != "qdrant":
            return {
                "collection_name": None,
                "index_version": self.index_version,
                "total_chunks": len(self.chunks),
                "total_articles": len({chunk.article_id for chunk in self.chunks}),
            }
        metadata = self.qdrant_store.get_index_metadata()
        if (
            metadata["index_version"] != self.index_version
            or metadata["collection_name"] != self._qdrant_collection_name
        ):
            self._qdrant_collection_name = metadata["collection_name"]
            self._apply_qdrant_snapshot(
                self.qdrant_store.load_chunks(),
                metadata["index_version"],
            )
        return metadata

    def close(self):
        if self.qdrant_store is not None:
            self.qdrant_store.close()

    # ========== 状态查询 ==========

    @property
    def is_ready(self) -> bool:
        if self.backend == "qdrant":
            return bool(self.qdrant_store.get_stats()["ready"])
        return self.index is not None and self.index.ntotal > 0

    @property
    def total_vectors(self) -> int:
        if self.backend == "qdrant":
            return int(self.qdrant_store.get_stats()["total_vectors"])
        return self.index.ntotal if self.index else 0

    @property
    def total_articles(self) -> int:
        if self.backend == "qdrant":
            return int(self.qdrant_store.get_stats()["total_articles"])
        article_ids = set(c.article_id for c in self.chunks)
        return len(article_ids)

    def get_stats(self) -> Dict:
        if self.backend == "qdrant":
            stats = self.qdrant_store.get_stats()
            self.refresh_shared_index_state()
            return {
                **stats,
                "backend": "qdrant",
                "model": self.model_name,
                "index_path": None,
            }
        return {
            "backend": "faiss",
            "ready": self.is_ready,
            "total_vectors": self.total_vectors,
            "total_chunks": len(self.chunks),
            "total_articles": self.total_articles,
            "model": self.model_name,
            "index_version": self.index_version,
            "index_path": INDEX_PATH,
        }


# 全局单例
rag_service = RAGService()
