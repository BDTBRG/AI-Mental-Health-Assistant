"""End-to-end service behavior using the real embedded Qdrant client."""

import sys
import unittest
from contextlib import nullcontext
from types import ModuleType
from pathlib import Path

import numpy as np
from qdrant_client import QdrantClient

try:
    import faiss  # noqa: F401
except ModuleNotFoundError:
    faiss_stub = ModuleType("faiss")
    faiss_stub.Index = object
    sys.modules["faiss"] = faiss_stub

try:
    import sentence_transformers  # noqa: F401
except ModuleNotFoundError:
    transformer_stub = ModuleType("sentence_transformers")
    transformer_stub.SentenceTransformer = object
    sys.modules["sentence_transformers"] = transformer_stub

try:
    import dotenv  # noqa: F401
except ModuleNotFoundError:
    dotenv_stub = ModuleType("dotenv")
    dotenv_stub.load_dotenv = lambda: None
    sys.modules["dotenv"] = dotenv_stub

AGENT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(AGENT_ROOT))

from services.qdrant_rag_store import QdrantRAGStore
from services.rag_service import RAGService


class FakeEmbeddingModel:
    def encode(self, texts, **_kwargs):
        vectors = []
        for text in texts:
            normalized = text.lower()
            if normalized == "queryvector rarephrase targetonly uniquephrase":
                vectors.append([1.0, 0.0])
            elif "semantic-decoy" in normalized:
                vectors.append([0.4, 0.916515])
            elif "压力" in normalized:
                vectors.append([1.0, 0.0])
            else:
                vectors.append([0.0, 1.0])
        return np.asarray(vectors, dtype="float32")


class RAGServiceQdrantTests(unittest.TestCase):
    def setUp(self):
        self.client = QdrantClient(":memory:")
        self.store = QdrantRAGStore(
            collection_alias="mental_health_knowledge",
            qdrant_client=self.client,
            write_lock_factory=nullcontext,
        )
        self.service = RAGService(backend="qdrant", qdrant_store=self.store)
        self.service.model = FakeEmbeddingModel()

    def tearDown(self):
        self.client.close()

    def test_rebuild_update_and_remove_are_visible_to_another_service_instance(self):
        count = self.service.rebuild_index([
            {"id": 10, "title": "压力支持", "content": "压力应对和情绪调节"},
            {"id": 20, "title": "睡眠知识", "content": "睡眠习惯与休息"},
        ])
        self.assertEqual(2, count)
        self.assertEqual(2, self.service.get_stats()["total_articles"])

        remote_service = RAGService(
            backend="qdrant",
            qdrant_store=QdrantRAGStore(
                collection_alias="mental_health_knowledge",
                qdrant_client=self.client,
                write_lock_factory=nullcontext,
            ),
        )
        remote_service.model = FakeEmbeddingModel()
        self.assertTrue(remote_service.load())
        self.assertEqual("压力支持", remote_service.search("压力", top_k=1)[0]["title"])

        self.service.update_article(10, "压力自助", "新的压力支持内容")
        updated = remote_service.search("压力", top_k=1)[0]
        self.assertEqual("压力自助", updated["title"])
        self.assertEqual("新的压力支持内容", updated["snippet"])

        self.service.remove_article(10)
        self.assertEqual(1, remote_service.get_stats()["total_articles"])
        self.assertEqual([], remote_service.search("压力", top_k=1))

    def test_lexical_recall_can_rescue_a_chunk_outside_dense_top_candidates(self):
        articles = [
            {
                "id": article_id,
                "title": f"semantic-decoy {article_id}",
                "content": "queryvector semantic-decoy related content",
            }
            for article_id in range(1, 6)
        ]
        articles.append({
            "id": 99,
            "title": "rarephrase targetonly uniquephrase",
            "content": "rarephrase targetonly uniquephrase",
        })
        self.service.rebuild_index(articles)

        results = self.service.search(
            "queryvector rarephrase targetonly uniquephrase",
            top_k=1,
        )

        self.assertEqual(99, results[0]["articleId"])


if __name__ == "__main__":
    unittest.main()
