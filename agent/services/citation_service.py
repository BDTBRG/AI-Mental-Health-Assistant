"""Fail closed when a generated knowledge citation cannot be verified."""

import re
from typing import Iterable, Tuple


SOURCE_CITATION_PATTERN = re.compile(
    r"\[article_id\s*=\s*(\d+)\s*,\s*chunk_id\s*=\s*(\d+)\s*\]",
    re.IGNORECASE,
)
SOURCE_MARKER_PATTERN = re.compile(r"\b(?:article_id|chunk_id)\b", re.IGNORECASE)

SOURCE_UNVERIFIABLE_RESPONSE = (
    "当前回复的知识库来源无法核验。"
    "为避免误导，我先不提供这部分结论；"
    "你可以查看知识库文章或换个更具体的问题。"
)


def review_source_citations(
    response: str,
    allowed_sources: Iterable[Tuple[object, object]],
    require_citation: bool = False,
) -> Tuple[str, bool]:
    """Return (reviewed response, rejected) for this turn's knowledge citations.

    The allowlist must come from structured retrieval metadata, never from text
    returned by an LLM or embedded in an article chunk.
    """
    allowed = {
        (str(article_id), str(chunk_id))
        for article_id, chunk_id in allowed_sources
        if article_id is not None and chunk_id is not None
    }
    citations = SOURCE_CITATION_PATTERN.findall(response)
    markers = SOURCE_MARKER_PATTERN.findall(response)

    # A canonical citation has exactly one article_id and one chunk_id. Count
    # every occurrence so wrapped, partial, or free-form fake citations fail too.
    malformed_or_unrecognized = len(markers) != 2 * len(citations)
    unverified = any((article_id, chunk_id) not in allowed
                     for article_id, chunk_id in citations)
    missing_required_citation = require_citation and not citations

    if malformed_or_unrecognized or unverified or missing_required_citation:
        return SOURCE_UNVERIFIABLE_RESPONSE, True
    return response, False
