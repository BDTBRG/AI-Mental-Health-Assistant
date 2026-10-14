"""Behavior tests for the shared, alias-backed Qdrant RAG store."""

from contextlib import nullcontext
import sys
import unittest
from pathlib import Path

from qdrant_client import QdrantClient

AGENT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(AGENT_ROOT))

from services.qdrant_rag_store import QdrantRAGStore


def chunk(chunk_id, article_id, content, char_start=0, title=None):
    return {
        "chunk_id": chunk_id,
        "article_id": article_id,
        "article_title": title or f"article-{article_id}",
        "content": content,
        "char_start": char_start,
        "char_end": char_start + len(content),
    }


def make_store():
    client = QdrantClient(":memory:")
    store = QdrantRAGStore(
        collection_alias="mental_health_knowledge",
        qdrant_client=client,
        write_lock_factory=nullcontext,
    )
    return client, store


class QdrantRAGStoreTests(unittest.TestCase):
    def setUp(self):
        self.client, self.store = make_store()

    def tearDown(self):
        self.client.close()

    def test_rebuild_atomically_switches_alias_and_keeps_previous_collection(self):
        first_stats = self.store.rebuild(
            [chunk(1, 10, "压力应对方法")],
            [[1.0, 0.0]],
            vector_size=2,
        )
        first_collection = self.store.active_collection_name

        second_stats = self.store.rebuild(
            [chunk(2, 20, "睡眠与情绪健康")],
            [[0.0, 1.0]],
            vector_size=2,
        )

        self.assertNotEqual(first_stats["index_version"], second_stats["index_version"])
        self.assertNotEqual(first_collection, self.store.active_collection_name)
        self.assertTrue(self.client.collection_exists(first_collection))
        self.assertEqual(1, second_stats["total_vectors"])
        self.assertEqual([20], [item["payload"]["article_id"] for item in self.store.load_chunks()])

    def test_article_sync_is_idempotent_and_removes_stale_chunks(self):
        self.store.rebuild(
            [
                chunk(1, 10, "旧版第一块", char_start=0),
                chunk(2, 10, "旧版第二块", char_start=384),
                chunk(3, 20, "其他文章内容", char_start=0),
            ],
            [[1.0, 0.0], [0.9, 0.1], [0.0, 1.0]],
            vector_size=2,
        )
        replacement = [chunk(101, 10, "新版内容", char_start=0, title="article-10-updated")]

        first_stats = self.store.sync_article(replacement, [[1.0, 0.0]], article_id=10)
        second_stats = self.store.sync_article(replacement, [[1.0, 0.0]], article_id=10)
        records = self.store.load_chunks()

        self.assertEqual(first_stats["index_version"], second_stats["index_version"])
        self.assertEqual(2, second_stats["total_vectors"])
        self.assertEqual({10, 20}, {record["payload"]["article_id"] for record in records})
        self.assertEqual(
            ["新版内容"],
            [record["payload"]["content"] for record in records
             if record["payload"]["article_id"] == 10],
        )

    def test_delete_article_removes_all_its_chunks_from_shared_collection(self):
        self.store.rebuild(
            [chunk(1, 10, "待下架内容"), chunk(2, 20, "保留内容")],
            [[1.0, 0.0], [0.0, 1.0]],
            vector_size=2,
        )

        stats = self.store.delete_article(article_id=10)

        self.assertEqual(1, stats["total_vectors"])
        self.assertEqual([20], [record["payload"]["article_id"] for record in self.store.load_chunks()])


if __name__ == "__main__":
    unittest.main()
