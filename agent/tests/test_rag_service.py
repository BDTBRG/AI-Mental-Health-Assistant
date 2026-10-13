import numpy as np

from services.rag_service import RAGService, TextChunk


class FakeModel:
    def encode(self, _texts, **_kwargs):
        return np.array([[1.0, 0.0]], dtype="float32")


class FakeIndex:
    ntotal = 2

    def search(self, _query, _limit):
        # FAISS IndexIDMap returns stable chunk IDs, not offsets in metadata.
        return np.array([[0.95, 0.83]], dtype="float32"), np.array([[41, 8]], dtype="int64")


def test_search_resolves_faiss_ids_after_metadata_compaction():
    service = RAGService()
    service.model = FakeModel()
    service.index = FakeIndex()
    service.chunks = [
        TextChunk(8, 2, "后续文章", "后续文章包含情绪压力相关内容", 0, 14),
        TextChunk(41, 3, "首位文章", "首位文章介绍一般心理知识", 0, 12),
    ]

    results = service.search("情绪压力", top_k=2)

    # The lexical reranker may change order, but every FAISS ID must resolve to its
    # own metadata even when metadata storage order differs from index ID order.
    results_by_id = {item["chunkId"]: item for item in results}
    assert results_by_id[41]["title"] == "首位文章"
    assert results_by_id[8]["title"] == "后续文章"
    assert results[0]["chunkId"] == 8
    assert results[0]["articleId"] == 2
    assert results[0]["lexicalScore"] > results[1]["lexicalScore"]
