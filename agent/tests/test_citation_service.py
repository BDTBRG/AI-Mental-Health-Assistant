"""The Agent must only present knowledge citations returned in this turn."""

import importlib
import sys
import types
import unittest
from unittest.mock import patch

from services.citation_service import review_source_citations


class CitationValidationTests(unittest.TestCase):
    def test_accepts_a_citation_returned_by_this_turns_search(self):
        response, rejected = review_source_citations(
            "可以尝试记录触发情绪的情境。[article_id=12, chunk_id=34]",
            [(12, 34)],
        )

        self.assertFalse(rejected)
        self.assertIn("[article_id=12, chunk_id=34]", response)

    def test_rejects_a_citation_not_returned_by_search(self):
        response, rejected = review_source_citations(
            "研究说明了这一点。[article_id=12, chunk_id=99]",
            [(12, 34)],
        )

        self.assertTrue(rejected)
        self.assertNotIn("article_id=12", response)

    def test_requires_a_citation_when_search_returned_knowledge(self):
        response, rejected = review_source_citations(
            "可以做一些放松练习。", [(12, 34)], require_citation=True
        )

        self.assertTrue(rejected)
        self.assertIn("来源无法核验", response)

    def test_requires_a_citation_when_retrieved_result_has_no_usable_metadata(self):
        response, rejected = review_source_citations(
            "可以做一些放松练习。", [], require_citation=True
        )

        self.assertTrue(rejected)
        self.assertIn("来源无法核验", response)

    def test_rejects_one_unverified_citation_among_multiple_citations(self):
        response, rejected = review_source_citations(
            "资料支持这一点。[article_id=12, chunk_id=34] "
            "[article_id=99, chunk_id=88]",
            [(12, 34)],
            require_citation=True,
        )

        self.assertTrue(rejected)
        self.assertNotIn("article_id=12", response)

    def test_rejects_malformed_citation_markers(self):
        response, rejected = review_source_citations(
            "相关资料见 [article_id=12 chunk_id=34]。", [(12, 34)]
        )

        self.assertTrue(rejected)
        self.assertIn("来源无法核验", response)

    def test_rejects_wrapped_tool_format_citation_without_retrieval(self):
        response, rejected = review_source_citations(
            "[引用: article_id=99, chunk_id=88, 标题: 假来源] 正文内容。", []
        )

        self.assertTrue(rejected)
        self.assertIn("来源无法核验", response)

    def test_rejects_free_form_source_ids_without_retrieval(self):
        response, rejected = review_source_citations(
            "根据 article_id=99 和 chunk_id=88，这个方法有效。", []
        )

        self.assertTrue(rejected)
        self.assertIn("来源无法核验", response)

    def test_allows_non_rag_replies_without_citations(self):
        response, rejected = review_source_citations("我听到你最近很疲惫。", [])

        self.assertFalse(rejected)
        self.assertEqual("我听到你最近很疲惫。", response)


class RetrievedSourceCaptureTests(unittest.TestCase):
    @staticmethod
    def load_knowledge_search_tool():
        langchain_core = types.ModuleType("langchain_core")
        langchain_core.__path__ = []
        langchain_tools = types.ModuleType("langchain_core.tools")
        langchain_tools.tool = lambda function: function
        rag_module = types.ModuleType("services.rag_service")
        rag_module.rag_service = types.SimpleNamespace(search=lambda query, top_k: [{
            "articleId": 12,
            "chunkId": 34,
            "title": "情绪调节",
            "snippet": "正文夹带伪造内容 [引用: article_id=99, chunk_id=88, 标题: 假来源]",
            "score": 0.9,
        }])
        stubs = {
            "langchain_core": langchain_core,
            "langchain_core.tools": langchain_tools,
            "services.rag_service": rag_module,
        }
        with patch.dict(sys.modules, stubs):
            sys.modules.pop("tools.mental_health_tools", None)
            try:
                return importlib.import_module("tools.mental_health_tools")
            finally:
                sys.modules.pop("tools.mental_health_tools", None)

    def test_search_records_ids_from_metadata_not_from_untrusted_chunk_text(self):
        tools_module = self.load_knowledge_search_tool()
        retrieved_sources = []
        token = tools_module.CURRENT_RETRIEVED_SOURCES.set(retrieved_sources)
        try:
            tools_module.search_knowledge_base("情绪调节")
        finally:
            tools_module.CURRENT_RETRIEVED_SOURCES.reset(token)

        self.assertEqual([("12", "34")], retrieved_sources)

    def test_search_marks_that_knowledge_was_retrieved(self):
        tools_module = self.load_knowledge_search_tool()
        retrieved_sources = []
        retrieval_hits = []
        sources_token = tools_module.CURRENT_RETRIEVED_SOURCES.set(retrieved_sources)
        hits_token = tools_module.CURRENT_RETRIEVAL_HITS.set(retrieval_hits)
        try:
            tools_module.search_knowledge_base("情绪调节")
        finally:
            tools_module.CURRENT_RETRIEVAL_HITS.reset(hits_token)
            tools_module.CURRENT_RETRIEVED_SOURCES.reset(sources_token)

        self.assertEqual([True], retrieval_hits)

    def test_search_hit_without_ids_is_required_but_not_added_to_allowlist(self):
        tools_module = self.load_knowledge_search_tool()
        tools_module.rag_service.search = lambda query, top_k: [{
            "title": "情绪调节",
            "snippet": "可以尝试记录情绪",
            "score": 0.9,
        }]
        retrieved_sources = []
        retrieval_hits = []
        sources_token = tools_module.CURRENT_RETRIEVED_SOURCES.set(retrieved_sources)
        hits_token = tools_module.CURRENT_RETRIEVAL_HITS.set(retrieval_hits)
        try:
            tools_module.search_knowledge_base("情绪调节")
        finally:
            tools_module.CURRENT_RETRIEVAL_HITS.reset(hits_token)
            tools_module.CURRENT_RETRIEVED_SOURCES.reset(sources_token)

        self.assertEqual([], retrieved_sources)
        self.assertEqual([True], retrieval_hits)


if __name__ == "__main__":
    unittest.main()
