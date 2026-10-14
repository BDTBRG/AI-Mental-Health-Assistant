"""Shared Qdrant storage for the Agent knowledge index.

Full rebuilds are published through an atomic collection-alias switch. Article
updates use deterministic point IDs and a Redis lock so retries and concurrent
Agent replicas converge on one shared collection.
"""

import hashlib
import logging
import uuid
from contextlib import contextmanager
from typing import Dict, Iterable, List, Optional, Sequence

logger = logging.getLogger(__name__)

_METADATA_NAMESPACE = uuid.UUID("2b22e6e4-8014-4e2b-9170-4615e531eb9c")
_CHUNK_NAMESPACE = uuid.UUID("fcd09908-bbf7-479c-92f8-33f3e0d85928")
_BATCH_SIZE = 128


class QdrantRAGStore:
    """Persists RAG vectors in a shared Qdrant collection alias."""

    def __init__(
        self,
        collection_alias: str,
        url: str = "http://localhost:6333",
        api_key: str = "",
        redis_url: str = "",
        lock_name: str = "amha:rag:index-write",
        lock_timeout_seconds: int = 1800,
        lock_wait_seconds: int = 60,
        qdrant_client=None,
        write_lock_factory=None,
    ):
        self.collection_alias = collection_alias
        self.url = url
        self.api_key = api_key
        self.redis_url = redis_url
        self.lock_name = lock_name
        self.lock_timeout_seconds = lock_timeout_seconds
        self.lock_wait_seconds = lock_wait_seconds
        self.client = qdrant_client
        self._models = None
        self._write_lock_factory = write_lock_factory

    @property
    def active_collection_name(self) -> Optional[str]:
        client = self._client()
        aliases = client.get_aliases().aliases
        for alias in aliases:
            if alias.alias_name == self.collection_alias:
                return alias.collection_name
        return None

    def _client(self):
        if self.client is None:
            from qdrant_client import QdrantClient

            self.client = QdrantClient(
                url=self.url,
                api_key=self.api_key or None,
                timeout=15,
            )
        return self.client

    def _model_types(self):
        if self._models is None:
            from qdrant_client import models

            self._models = models
        return self._models

    def close(self):
        if self.client is not None:
            self.client.close()
            self.client = None

    @contextmanager
    def _write_guard(self):
        if self._write_lock_factory is not None:
            with self._write_lock_factory():
                yield
            return
        if not self.redis_url.strip():
            raise RuntimeError("RAG_WRITE_LOCK_REDIS_URL is required for the Qdrant backend")

        from redis import Redis

        redis_client = Redis.from_url(
            self.redis_url,
            socket_connect_timeout=5,
            socket_timeout=30,
        )
        lock = redis_client.lock(
            self.lock_name,
            timeout=self.lock_timeout_seconds,
            blocking_timeout=self.lock_wait_seconds,
        )
        acquired = False
        try:
            acquired = lock.acquire(blocking=True)
            if not acquired:
                raise TimeoutError("Timed out waiting for the shared RAG write lock")
            yield
        finally:
            try:
                if acquired:
                    lock.release()
            finally:
                redis_client.close()

    @staticmethod
    def _stable_point_id(article_id: int, char_start: int) -> str:
        return str(uuid.uuid5(_CHUNK_NAMESPACE, f"{article_id}:{char_start}"))

    @classmethod
    def _stable_chunk_id(cls, article_id: int, char_start: int) -> int:
        point_uuid = uuid.UUID(cls._stable_point_id(article_id, char_start))
        return point_uuid.int & ((1 << 63) - 1)

    @staticmethod
    def _metadata_point_id() -> str:
        return str(uuid.uuid5(_METADATA_NAMESPACE, "index-metadata"))

    def _chunk_filter(self, article_id: Optional[int] = None):
        models = self._model_types()
        conditions = [
            models.FieldCondition(key="kind", match=models.MatchValue(value="chunk"))
        ]
        if article_id is not None:
            conditions.append(
                models.FieldCondition(
                    key="article_id", match=models.MatchValue(value=int(article_id))
                )
            )
        return models.Filter(must=conditions)

    def _scroll(self, collection_name: str, article_id: Optional[int] = None) -> List:
        client = self._client()
        records = []
        offset = None
        while True:
            page, offset = client.scroll(
                collection_name=collection_name,
                scroll_filter=self._chunk_filter(article_id),
                limit=_BATCH_SIZE,
                with_payload=True,
                with_vectors=False,
                offset=offset,
            )
            records.extend(page)
            if offset is None:
                return records

    def load_chunks(self) -> List[Dict]:
        collection_name = self.active_collection_name
        if collection_name is None:
            return []
        return [
            {"id": point.id, "payload": point.payload or {}}
            for point in self._scroll(collection_name)
        ]

    def _manifest(self, collection_name: str) -> Optional[Dict]:
        points = self._client().retrieve(
            collection_name=collection_name,
            ids=[self._metadata_point_id()],
            with_payload=True,
            with_vectors=False,
        )
        if not points:
            return None
        return points[0].payload or {}

    @staticmethod
    def _compute_version(chunks: Sequence[Dict]) -> str:
        digest = hashlib.sha256()
        ordered = sorted(
            chunks,
            key=lambda chunk: (int(chunk["article_id"]), int(chunk["char_start"])),
        )
        for chunk in ordered:
            digest.update(str(int(chunk["article_id"])).encode("utf-8"))
            digest.update(b"\0")
            digest.update(str(chunk["article_title"]).encode("utf-8"))
            digest.update(b"\0")
            digest.update(str(chunk["content"]).encode("utf-8"))
            digest.update(b"\0")
        return digest.hexdigest()[:16]

    def _point(self, chunk: Dict, vector: Sequence[float]):
        models = self._model_types()
        article_id = int(chunk["article_id"])
        char_start = int(chunk["char_start"])
        payload = {
            "kind": "chunk",
            "chunk_id": self._stable_chunk_id(article_id, char_start),
            "article_id": article_id,
            "article_title": str(chunk["article_title"]),
            "content": str(chunk["content"]),
            "char_start": char_start,
            "char_end": int(chunk["char_end"]),
        }
        return models.PointStruct(
            id=self._stable_point_id(article_id, char_start),
            vector=[float(value) for value in vector],
            payload=payload,
        )

    def _upsert_batches(self, collection_name: str, points: Sequence):
        client = self._client()
        for offset in range(0, len(points), _BATCH_SIZE):
            client.upsert(
                collection_name=collection_name,
                points=list(points[offset:offset + _BATCH_SIZE]),
                wait=True,
            )

    def _upsert_manifest(
        self,
        collection_name: str,
        chunks: Sequence[Dict],
        index_version: str,
        vector_size: int,
    ):
        models = self._model_types()
        article_ids = {int(chunk["article_id"]) for chunk in chunks}
        metadata_point = models.PointStruct(
            id=self._metadata_point_id(),
            vector=[1.0] + [0.0] * (vector_size - 1),
            payload={
                "kind": "metadata",
                "index_version": index_version,
                "total_chunks": len(chunks),
                "total_articles": len(article_ids),
            },
        )
        self._client().upsert(
            collection_name=collection_name,
            points=[metadata_point],
            wait=True,
        )

    def _stats(self, collection_name: Optional[str]) -> Dict:
        if collection_name is None:
            return {
                "ready": False,
                "total_vectors": 0,
                "total_chunks": 0,
                "total_articles": 0,
                "index_version": "unbuilt",
                "collection": self.collection_alias,
            }
        count = self._client().count(
            collection_name=collection_name,
            count_filter=self._chunk_filter(),
            exact=True,
        ).count
        manifest = self._manifest(collection_name) or {}
        return {
            "ready": count > 0,
            "total_vectors": count,
            "total_chunks": int(manifest.get("total_chunks", count)),
            "total_articles": int(manifest.get("total_articles", 0)),
            "index_version": manifest.get("index_version", "unbuilt"),
            "collection": self.collection_alias,
        }

    def get_stats(self) -> Dict:
        return self._stats(self.active_collection_name)

    def get_index_metadata(self) -> Dict:
        collection_name = self.active_collection_name
        if collection_name is None:
            return {
                "collection_name": None,
                "index_version": "unbuilt",
                "total_chunks": 0,
                "total_articles": 0,
            }
        manifest = self._manifest(collection_name) or {}
        return {
            "collection_name": collection_name,
            "index_version": manifest.get("index_version", "unbuilt"),
            "total_chunks": int(manifest.get("total_chunks", 0)),
            "total_articles": int(manifest.get("total_articles", 0)),
        }

    def _create_collection(self, vector_size: int) -> str:
        models = self._model_types()
        suffix = uuid.uuid4().hex[:16]
        alias_digest = hashlib.sha256(self.collection_alias.encode("utf-8")).hexdigest()[:8]
        collection_name = f"amha_rag_{alias_digest}_{suffix}"
        self._client().create_collection(
            collection_name=collection_name,
            vectors_config=models.VectorParams(
                size=vector_size,
                distance=models.Distance.COSINE,
            ),
        )
        return collection_name

    def _switch_alias(self, collection_name: str, previous_collection: Optional[str]):
        models = self._model_types()
        operations = []
        if previous_collection is not None:
            operations.append(models.DeleteAliasOperation(
                delete_alias=models.DeleteAlias(alias_name=self.collection_alias)
            ))
        operations.append(models.CreateAliasOperation(
            create_alias=models.CreateAlias(
                collection_name=collection_name,
                alias_name=self.collection_alias,
            )
        ))
        self._client().update_collection_aliases(operations)

    def _verify_collection(self, collection_name: str, chunks: Sequence[Dict], index_version: str):
        stats = self._stats(collection_name)
        if stats["total_vectors"] != len(chunks):
            raise RuntimeError("Qdrant staging collection chunk count does not match the source")
        if stats["index_version"] != index_version:
            raise RuntimeError("Qdrant staging collection index version does not match the source")
        actual = self._scroll(collection_name)
        expected_ids = {
            self._stable_point_id(int(chunk["article_id"]), int(chunk["char_start"]))
            for chunk in chunks
        }
        actual_ids = {str(point.id) for point in actual}
        if actual_ids != expected_ids:
            raise RuntimeError("Qdrant staging collection records do not match the source")

    def _rebuild_locked(
        self,
        chunks: Sequence[Dict],
        vectors: Sequence[Sequence[float]],
        vector_size: int,
    ) -> Dict:
        if len(chunks) != len(vectors):
            raise ValueError("Qdrant chunk and embedding counts must match")
        for vector in vectors:
            if len(vector) != vector_size:
                raise ValueError("Qdrant embedding dimension does not match vector_size")

        client = self._client()
        previous_collection = self.active_collection_name
        collection_name = self._create_collection(vector_size)
        points = [self._point(chunk, vector) for chunk, vector in zip(chunks, vectors)]
        self._upsert_batches(collection_name, points)
        index_version = self._compute_version(chunks)
        self._upsert_manifest(collection_name, chunks, index_version, vector_size)
        self._verify_collection(collection_name, chunks, index_version)
        self._switch_alias(collection_name, previous_collection)
        logger.info(
            "Published Qdrant RAG collection with %s chunks and version %s",
            len(chunks),
            index_version,
        )
        return self._stats(collection_name)

    def rebuild(
        self,
        chunks: Sequence[Dict],
        vectors: Sequence[Sequence[float]],
        vector_size: int,
    ) -> Dict:
        with self._write_guard():
            return self._rebuild_locked(chunks, vectors, vector_size)

    def _all_chunks_for(self, collection_name: str) -> List[Dict]:
        return [point.payload or {} for point in self._scroll(collection_name)]

    def _publish_incremental_manifest(self, collection_name: str, vector_size: int) -> Dict:
        chunks = self._all_chunks_for(collection_name)
        index_version = self._compute_version(chunks)
        self._upsert_manifest(collection_name, chunks, index_version, vector_size)
        return self._stats(collection_name)

    def _collection_vector_size(self, collection_name: str) -> int:
        config = self._client().get_collection(collection_name).config.params.vectors
        if hasattr(config, "size"):
            return int(config.size)
        if isinstance(config, dict) and config:
            first = next(iter(config.values()))
            if hasattr(first, "size"):
                return int(first.size)
        raise RuntimeError("Unable to determine Qdrant collection vector size")

    def sync_article(
        self,
        chunks: Sequence[Dict],
        vectors: Sequence[Sequence[float]],
        article_id: int,
        vector_size: Optional[int] = None,
    ) -> Dict:
        article_id = int(article_id)
        if len(chunks) != len(vectors):
            raise ValueError("Qdrant chunk and embedding counts must match")
        if any(int(chunk["article_id"]) != article_id for chunk in chunks):
            raise ValueError("Qdrant article update contains a different article ID")

        with self._write_guard():
            collection_name = self.active_collection_name
            if collection_name is None:
                if not chunks:
                    return self._stats(None)
                return self._rebuild_locked(
                    chunks,
                    vectors,
                    int(vector_size or len(vectors[0])),
                )

            expected_vector_size = self._collection_vector_size(collection_name)
            if vector_size is not None and int(vector_size) != expected_vector_size:
                raise ValueError("Qdrant embedding dimension does not match the collection")
            if any(len(vector) != expected_vector_size for vector in vectors):
                raise ValueError("Qdrant embedding dimension does not match the collection")

            old_points = self._scroll(collection_name, article_id)
            new_points = [self._point(chunk, vector) for chunk, vector in zip(chunks, vectors)]
            new_ids = {str(point.id) for point in new_points}
            self._upsert_batches(collection_name, new_points)
            stale_ids = [point.id for point in old_points if str(point.id) not in new_ids]
            if stale_ids:
                models = self._model_types()
                self._client().delete(
                    collection_name=collection_name,
                    points_selector=models.PointIdsList(points=stale_ids),
                    wait=True,
                )
            return self._publish_incremental_manifest(collection_name, expected_vector_size)

    def delete_article(self, article_id: int) -> Dict:
        with self._write_guard():
            collection_name = self.active_collection_name
            if collection_name is None:
                return self._stats(None)
            old_points = self._scroll(collection_name, int(article_id))
            if old_points:
                models = self._model_types()
                self._client().delete(
                    collection_name=collection_name,
                    points_selector=models.PointIdsList(points=[point.id for point in old_points]),
                    wait=True,
                )
            return self._publish_incremental_manifest(
                collection_name,
                self._collection_vector_size(collection_name),
            )

    def search(self, query_vector: Sequence[float], limit: int) -> List:
        if self.active_collection_name is None:
            return []
        response = self._client().query_points(
            collection_name=self.collection_alias,
            query=[float(value) for value in query_vector],
            query_filter=self._chunk_filter(),
            limit=max(1, int(limit)),
            with_payload=True,
            with_vectors=False,
        )
        return response.points
