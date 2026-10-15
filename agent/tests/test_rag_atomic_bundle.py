"""Failure-injection tests for local FAISS snapshot publication."""

import importlib.util
import copy
import json
import os
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch


AGENT_ROOT = Path(__file__).resolve().parents[1]
RAG_SERVICE_PATH = AGENT_ROOT / "services" / "rag_service.py"


class FakeFaissIndex:
    def __init__(self, ids=()):
        if isinstance(ids, FakeFlatIndex):
            ids = ()
        self.id_map = [int(chunk_id) for chunk_id in ids]
        self.ntotal = len(self.id_map)

    def add_with_ids(self, _embeddings, ids):
        self.id_map.extend(int(chunk_id) for chunk_id in ids)
        self.ntotal = len(self.id_map)

    def remove_ids(self, ids):
        removed = {int(chunk_id) for chunk_id in ids}
        self.id_map = [chunk_id for chunk_id in self.id_map if chunk_id not in removed]
        self.ntotal = len(self.id_map)


class FakeFlatIndex:
    def __init__(self, dimension):
        self.dimension = dimension


class FakeArray(list):
    @property
    def shape(self):
        return len(self), len(self[0]) if self else 0

    def astype(self, _dtype):
        return self


def load_rag_service():
    faiss_module = types.ModuleType("faiss")
    faiss_module.Index = FakeFaissIndex
    faiss_module.IndexIDMap = FakeFaissIndex
    faiss_module.IndexFlatIP = FakeFlatIndex
    faiss_module.IndexIDMap2 = FakeFaissIndex
    faiss_module.vector_to_array = lambda values: list(values)
    faiss_module.clone_index = copy.deepcopy
    faiss_module.write_index = lambda index, path: Path(path).write_text(
        json.dumps(index.id_map), encoding="utf-8"
    )
    faiss_module.read_index = lambda path: FakeFaissIndex(
        json.loads(Path(path).read_text(encoding="utf-8"))
    )

    numpy_module = types.ModuleType("numpy")
    numpy_module.ndarray = object
    numpy_module.array = lambda values, dtype=None: FakeArray(values)
    sentence_module = types.ModuleType("sentence_transformers")
    sentence_module.SentenceTransformer = lambda name: object()
    qdrant_module = types.ModuleType("services.qdrant_rag_store")
    qdrant_module.QdrantRAGStore = object
    config_module = types.ModuleType("config")
    config_module.RAG_BACKEND = "faiss"

    stubs = {
        "faiss": faiss_module,
        "numpy": numpy_module,
        "sentence_transformers": sentence_module,
        "services.qdrant_rag_store": qdrant_module,
        "config": config_module,
    }
    module_name = "rag_service_atomic_bundle_under_test"
    spec = importlib.util.spec_from_file_location(module_name, RAG_SERVICE_PATH)
    module = importlib.util.module_from_spec(spec)
    with patch.dict(sys.modules, stubs), patch("os.makedirs"):
        spec.loader.exec_module(module)
    return module


class RAGAtomicBundleTests(unittest.TestCase):
    def setUp(self):
        self.module = load_rag_service()
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.data_dir = Path(self.temp_dir.name)
        self.patches = [
            patch.object(self.module, "DATA_DIR", str(self.data_dir)),
            patch.object(self.module, "INDEX_PATH", str(self.data_dir / "faiss.index")),
            patch.object(self.module, "META_PATH", str(self.data_dir / "chunks_meta.json")),
            patch.object(self.module, "BUNDLES_DIR", str(self.data_dir / "bundles"), create=True),
            patch.object(self.module, "CURRENT_POINTER_PATH", str(self.data_dir / "CURRENT"), create=True),
        ]
        for patcher in self.patches:
            patcher.start()
            self.addCleanup(patcher.stop)
        self.service = self.module.RAGService(backend="faiss")

    def snapshot(self, chunk_ids):
        chunks = [
            self.module.TextChunk(
                chunk_id=chunk_id,
                article_id=7,
                article_title="情绪调节",
                content="记录触发情绪的情境。",
                char_start=index * 10,
                char_end=(index + 1) * 10,
            )
            for index, chunk_id in enumerate(chunk_ids)
        ]
        index = FakeFaissIndex(chunk_ids)
        version = self.service._compute_index_version(chunks)
        return index, chunks, version, max(chunk_ids, default=-1) + 1

    def publish(self, chunk_ids):
        snapshot = self.snapshot(chunk_ids)
        self.service._save_state(*snapshot)
        return snapshot

    def activate(self, snapshot):
        index, chunks, version, next_chunk_id = snapshot
        self.service.index = index
        self.service.chunks = chunks
        self.service.index_version = version
        self.service._next_chunk_id = next_chunk_id

    def test_pointer_replace_failure_keeps_previous_bundle_loadable(self):
        first = self.publish([10, 11])
        current_path = Path(self.module.CURRENT_POINTER_PATH)
        previous_pointer = current_path.read_bytes()
        second = self.snapshot([20, 21])
        real_replace = os.replace

        def fail_pointer_replace(source, destination):
            if os.fspath(destination) == os.fspath(current_path):
                raise OSError("simulated pointer switch failure")
            return real_replace(source, destination)

        with patch.object(self.module.os, "replace", side_effect=fail_pointer_replace):
            with self.assertRaisesRegex(OSError, "pointer switch failure"):
                self.service._save_state(*second)

        self.assertEqual(previous_pointer, current_path.read_bytes())
        reader = self.module.RAGService(backend="faiss")
        self.assertTrue(reader.load())
        self.assertEqual([chunk.chunk_id for chunk in first[1]], [
            chunk.chunk_id for chunk in reader.chunks
        ])
        self.assertEqual(first[2], reader.index_version)

    def test_index_write_failure_keeps_previous_bundle_loadable(self):
        first = self.publish([10, 11])
        previous_pointer = Path(self.module.CURRENT_POINTER_PATH).read_bytes()
        second = self.snapshot([20, 21])

        with patch.object(
            self.module.faiss,
            "write_index",
            side_effect=OSError("simulated staged index write failure"),
        ):
            with self.assertRaisesRegex(OSError, "staged index write failure"):
                self.service._save_state(*second)

        self.assertEqual(previous_pointer, Path(self.module.CURRENT_POINTER_PATH).read_bytes())
        self.assertEqual([], list(Path(self.module.BUNDLES_DIR).glob(".pending-*")))
        reader = self.module.RAGService(backend="faiss")
        self.assertTrue(reader.load())
        self.assertEqual([10, 11], [chunk.chunk_id for chunk in reader.chunks])
        self.assertEqual(first[2], reader.index_version)

    def test_same_count_but_mismatched_faiss_ids_is_rejected(self):
        self.publish([10, 11])
        pointer = json.loads(Path(self.module.CURRENT_POINTER_PATH).read_text("utf-8"))
        metadata_path = Path(self.module.BUNDLES_DIR) / pointer["generation"] / "chunks_meta.json"
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        metadata["chunks"][0]["chunk_id"] = 99
        metadata_path.write_text(json.dumps(metadata), encoding="utf-8")
        pointer["metadata_sha256"] = self.module.RAGService._file_sha256(str(metadata_path))
        Path(self.module.CURRENT_POINTER_PATH).write_text(
            json.dumps(pointer), encoding="utf-8"
        )

        reader = self.module.RAGService(backend="faiss")

        self.assertFalse(reader.load())
        self.assertIsNone(reader.index)
        self.assertEqual([], reader.chunks)

    def test_bundle_checksum_mismatch_is_rejected(self):
        self.publish([10, 11])
        pointer = json.loads(Path(self.module.CURRENT_POINTER_PATH).read_text("utf-8"))
        metadata_path = Path(self.module.BUNDLES_DIR) / pointer["generation"] / "chunks_meta.json"
        metadata_path.write_text(
            metadata_path.read_text(encoding="utf-8") + "\n",
            encoding="utf-8",
        )

        reader = self.module.RAGService(backend="faiss")

        self.assertFalse(reader.load())
        self.assertIsNone(reader.index)

    def test_legacy_flat_files_remain_loadable(self):
        index, chunks, version, next_id = self.snapshot([5, 8])
        self.module.faiss.write_index(index, self.module.INDEX_PATH)
        metadata = {
            "next_chunk_id": next_id,
            "index_version": version,
            "chunks": [chunk.__dict__ for chunk in chunks],
        }
        Path(self.module.META_PATH).write_text(
            json.dumps(metadata), encoding="utf-8"
        )

        reader = self.module.RAGService(backend="faiss")

        self.assertTrue(reader.load())
        self.assertEqual([5, 8], [chunk.chunk_id for chunk in reader.chunks])
        self.assertEqual(version, reader.index_version)

    def test_nonempty_rebuild_publishes_candidate_snapshot(self):
        self.service.model = types.SimpleNamespace(
            encode=lambda texts, **_kwargs: FakeArray([[0.1, 0.2] for _ in texts]),
            get_sentence_embedding_dimension=lambda: 2,
        )

        self.assertEqual(
            1,
            self.service.rebuild_index([
                {"id": 7, "title": "情绪调节", "content": "记录触发情绪的情境。"}
            ]),
        )

        self.assertEqual([0], self.module.faiss.vector_to_array(self.service.index.id_map))
        self.assertEqual([0], [chunk.chunk_id for chunk in self.service.chunks])
        reader = self.module.RAGService(backend="faiss")
        self.assertTrue(reader.load())
        self.assertEqual([0], [chunk.chunk_id for chunk in reader.chunks])
        self.assertEqual(self.service.index_version, reader.index_version)

    def test_empty_rebuild_publishes_empty_snapshot_and_preserves_legacy_files(self):
        previous = self.publish([10, 11])
        self.activate(previous)
        legacy_index = Path(self.module.INDEX_PATH)
        legacy_meta = Path(self.module.META_PATH)
        legacy_index.write_text("legacy-index", encoding="utf-8")
        legacy_meta.write_text("legacy-metadata", encoding="utf-8")
        self.service.model = types.SimpleNamespace(
            encode=lambda texts, **_kwargs: FakeArray([[0.1, 0.2] for _ in texts]),
            get_sentence_embedding_dimension=lambda: 2
        )

        self.assertEqual(0, self.service.rebuild_index([]))

        current = json.loads(Path(self.module.CURRENT_POINTER_PATH).read_text("utf-8"))
        self.assertNotEqual(previous[2], current["index_version"])
        self.assertEqual("legacy-index", legacy_index.read_text(encoding="utf-8"))
        self.assertEqual("legacy-metadata", legacy_meta.read_text(encoding="utf-8"))
        self.assertEqual([], self.service.chunks)
        self.assertEqual(0, self.service.index.ntotal)

        reader = self.module.RAGService(backend="faiss")
        self.assertFalse(reader.load())
        self.assertEqual([], reader.chunks)
        self.assertEqual(0, reader.total_vectors)
        self.assertFalse(reader.is_ready)

    def test_empty_rebuild_pointer_failure_keeps_old_in_memory_and_disk_state(self):
        previous = self.publish([10, 11])
        self.activate(previous)
        current_path = Path(self.module.CURRENT_POINTER_PATH)
        previous_pointer = current_path.read_bytes()
        old_index = self.service.index
        self.service.model = types.SimpleNamespace(
            get_sentence_embedding_dimension=lambda: 2
        )
        real_replace = os.replace

        def fail_pointer_replace(source, destination):
            if os.fspath(destination) == os.fspath(current_path):
                raise OSError("simulated empty snapshot switch failure")
            return real_replace(source, destination)

        with patch.object(self.module.os, "replace", side_effect=fail_pointer_replace):
            with self.assertRaisesRegex(OSError, "empty snapshot switch failure"):
                self.service.rebuild_index([])

        self.assertEqual(previous_pointer, current_path.read_bytes())
        self.assertIs(old_index, self.service.index)
        self.assertEqual([10, 11], [chunk.chunk_id for chunk in self.service.chunks])
        self.assertEqual(previous[2], self.service.index_version)

    def test_nonempty_rebuild_pointer_failure_keeps_old_in_memory_state(self):
        previous = self.publish([10, 11])
        self.activate(previous)
        old_index = self.service.index
        old_chunks = list(self.service.chunks)
        old_pointer = Path(self.module.CURRENT_POINTER_PATH).read_bytes()
        self.service.model = types.SimpleNamespace(
            encode=lambda texts, **_kwargs: FakeArray([[0.1, 0.2] for _ in texts]),
            get_sentence_embedding_dimension=lambda: 2,
        )
        current_path = Path(self.module.CURRENT_POINTER_PATH)
        real_replace = os.replace

        def fail_pointer_replace(source, destination):
            if os.fspath(destination) == os.fspath(current_path):
                raise OSError("simulated rebuild snapshot switch failure")
            return real_replace(source, destination)

        with patch.object(self.module.os, "replace", side_effect=fail_pointer_replace):
            with self.assertRaisesRegex(OSError, "rebuild snapshot switch failure"):
                self.service.rebuild_index([{"id": 99, "title": "新文章", "content": "新内容"}])

        self.assertEqual(old_pointer, current_path.read_bytes())
        self.assertIs(old_index, self.service.index)
        self.assertEqual(old_chunks, self.service.chunks)
        self.assertEqual(previous[2], self.service.index_version)
        self.assertEqual(previous[3], self.service._next_chunk_id)

    def test_add_article_pointer_failure_does_not_publish_candidate_in_memory(self):
        previous = self.publish([10])
        self.activate(previous)
        old_index = self.service.index
        old_chunks = list(self.service.chunks)
        old_pointer = Path(self.module.CURRENT_POINTER_PATH).read_bytes()
        self.service.model = types.SimpleNamespace(
            encode=lambda texts, **_kwargs: FakeArray([[0.1, 0.2] for _ in texts]),
            get_sentence_embedding_dimension=lambda: 2,
        )
        current_path = Path(self.module.CURRENT_POINTER_PATH)
        real_replace = os.replace

        def fail_pointer_replace(source, destination):
            if os.fspath(destination) == os.fspath(current_path):
                raise OSError("simulated add snapshot switch failure")
            return real_replace(source, destination)

        with patch.object(self.module.os, "replace", side_effect=fail_pointer_replace):
            with self.assertRaisesRegex(OSError, "add snapshot switch failure"):
                self.service.add_article(12, "新增文章", "新增内容")

        self.assertEqual(old_pointer, current_path.read_bytes())
        self.assertIs(old_index, self.service.index)
        self.assertEqual(old_chunks, self.service.chunks)
        self.assertEqual(previous[2], self.service.index_version)
        self.assertEqual(previous[3], self.service._next_chunk_id)

    def test_remove_article_publishes_empty_snapshot(self):
        previous = self.publish([10])
        self.activate(previous)

        self.service.remove_article(7)

        self.assertEqual([], self.service.chunks)
        self.assertEqual(0, self.service.index.ntotal)
        reader = self.module.RAGService(backend="faiss")
        self.assertFalse(reader.load())
        self.assertEqual([], reader.chunks)
        self.assertEqual(0, reader.total_vectors)

    def test_update_article_pointer_failure_does_not_publish_candidate_in_memory(self):
        previous = self.publish([10])
        self.activate(previous)
        old_index = self.service.index
        old_chunks = list(self.service.chunks)
        old_pointer = Path(self.module.CURRENT_POINTER_PATH).read_bytes()
        self.service.model = types.SimpleNamespace(
            encode=lambda texts, **_kwargs: FakeArray([[0.1, 0.2] for _ in texts]),
            get_sentence_embedding_dimension=lambda: 2,
        )
        current_path = Path(self.module.CURRENT_POINTER_PATH)
        real_replace = os.replace

        def fail_pointer_replace(source, destination):
            if os.fspath(destination) == os.fspath(current_path):
                raise OSError("simulated update snapshot switch failure")
            return real_replace(source, destination)

        with patch.object(self.module.os, "replace", side_effect=fail_pointer_replace):
            with self.assertRaisesRegex(OSError, "update snapshot switch failure"):
                self.service.update_article(7, "更新标题", "更新正文")

        self.assertEqual(old_pointer, current_path.read_bytes())
        self.assertIs(old_index, self.service.index)
        self.assertEqual(old_chunks, self.service.chunks)
        self.assertEqual(previous[2], self.service.index_version)
        self.assertEqual(previous[3], self.service._next_chunk_id)

    def test_update_article_publishes_replacement_in_one_snapshot(self):
        previous = self.publish([10])
        self.activate(previous)
        self.service.model = types.SimpleNamespace(
            encode=lambda texts, **_kwargs: FakeArray([[0.1, 0.2] for _ in texts]),
            get_sentence_embedding_dimension=lambda: 2,
        )

        self.service.update_article(7, "更新标题", "更新正文")

        self.assertEqual([(11, "更新标题")], [
            (chunk.chunk_id, chunk.article_title) for chunk in self.service.chunks
        ])
        self.assertEqual([11], self.module.faiss.vector_to_array(self.service.index.id_map))
        self.assertEqual(12, self.service._next_chunk_id)
        reader = self.module.RAGService(backend="faiss")
        self.assertTrue(reader.load())
        self.assertEqual([(11, "更新标题")], [
            (chunk.chunk_id, chunk.article_title) for chunk in reader.chunks
        ])


if __name__ == "__main__":
    unittest.main()
