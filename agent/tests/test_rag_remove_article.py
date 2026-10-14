import pytest

import services.rag_service as rag_module
from services.rag_service import RAGService, TextChunk


class MutableFakeIndex:
    def __init__(self, chunk_ids):
        self.chunk_ids = list(chunk_ids)
        self.ntotal = len(self.chunk_ids)

    def clone(self):
        return MutableFakeIndex(self.chunk_ids)

    def remove_ids(self, ids):
        removed = set(int(chunk_id) for chunk_id in ids)
        self.chunk_ids = [chunk_id for chunk_id in self.chunk_ids if chunk_id not in removed]
        self.ntotal = len(self.chunk_ids)


def service_with_two_articles():
    service = RAGService.__new__(RAGService)
    service.index = MutableFakeIndex([0, 1, 2])
    service.chunks = [
        TextChunk(0, 7, "待删除", "文章 7 内容", 0, 6),
        TextChunk(1, 7, "待删除", "文章 7 后续", 6, 12),
        TextChunk(2, 8, "保留", "文章 8 内容", 0, 6),
    ]
    service._next_chunk_id = 3
    service.index_version = "before-delete"
    return service


def test_remove_article_restores_memory_after_persistence_failure_then_retry_succeeds(monkeypatch):
    monkeypatch.setattr(rag_module.faiss, "clone_index", lambda index: index.clone())
    service = service_with_two_articles()
    original_index = service.index
    original_chunks = list(service.chunks)
    original_version = service.index_version
    calls = 0

    def fail_first_save(_index, _chunks, _version, _next_chunk_id):
        nonlocal calls
        calls += 1
        if calls == 1:
            raise OSError("simulated persistence failure")

    monkeypatch.setattr(service, "_save_state", fail_first_save, raising=False)
    with pytest.raises(OSError, match="simulated persistence failure"):
        service.remove_article(7)

    assert service.index is original_index
    assert service.chunks == original_chunks
    assert service.index_version == original_version

    saved = {}

    def save_candidate(index, chunks, version, next_chunk_id):
        saved.update(index=index, chunks=list(chunks), version=version, next_chunk_id=next_chunk_id)

    monkeypatch.setattr(service, "_save_state", save_candidate)
    service.remove_article(7)

    assert service.index is saved["index"]
    assert [chunk.article_id for chunk in service.chunks] == [8]
    assert service.index_version == saved["version"]
    assert saved["next_chunk_id"] == 3
    assert service.index.chunk_ids == [2]

    committed_index = service.index
    service.remove_article(7)
    assert service.index is committed_index
    assert service.index.chunk_ids == [2]
