"""Strict, bounded context summaries for long-running chat sessions."""

import json
from typing import Any

from langchain_core.messages import HumanMessage, SystemMessage
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator, model_validator

from prompts.context_summary_prompt import (
    CONTEXT_SUMMARY_PROMPT_VERSION,
    CONTEXT_SUMMARY_SYSTEM_PROMPT,
)

MAX_SUMMARY_ITEMS_PER_FIELD = 8
MAX_SUMMARY_ITEM_CHARS = 240
MAX_SUMMARY_TEXT_CHARS = 1200
MAX_SOURCE_IDS_PER_ITEM = 5
MAX_INPUT_MESSAGES = 40
MAX_INPUT_TEXT_CHARS = 16000


class ContextSummaryError(ValueError):
    """Base class for errors that must prevent summary advancement."""

    code = "CONTEXT_SUMMARY_INVALID"


class ContextSummaryInputError(ContextSummaryError):
    code = "CONTEXT_SUMMARY_INVALID_INPUT"


class ContextSummaryOutputError(ContextSummaryError):
    code = "CONTEXT_SUMMARY_INVALID_OUTPUT"


class ContextSummaryEntry(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
        populate_by_name=True,
        strict=True,
        str_strip_whitespace=True,
    )

    text: str = Field(min_length=1, max_length=MAX_SUMMARY_ITEM_CHARS)
    source_message_ids: list[int] = Field(
        alias="sourceMessageIds",
        min_length=1,
        max_length=MAX_SOURCE_IDS_PER_ITEM,
    )

    @field_validator("source_message_ids")
    @classmethod
    def source_ids_are_positive_and_unique(cls, value: list[int]) -> list[int]:
        if any(source_id <= 0 for source_id in value):
            raise ValueError("source message IDs must be positive")
        if len(set(value)) != len(value):
            raise ValueError("source message IDs must be unique")
        return value


class ContextSummary(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True, strict=True)

    user_stated_context: list[ContextSummaryEntry] = Field(
        alias="userStatedContext",
        max_length=MAX_SUMMARY_ITEMS_PER_FIELD,
    )
    user_preferences: list[ContextSummaryEntry] = Field(
        alias="userPreferences",
        max_length=MAX_SUMMARY_ITEMS_PER_FIELD,
    )
    open_topics: list[ContextSummaryEntry] = Field(
        alias="openTopics",
        max_length=MAX_SUMMARY_ITEMS_PER_FIELD,
    )

    @model_validator(mode="after")
    def text_is_within_aggregate_limit(self) -> "ContextSummary":
        total_chars = sum(
            len(entry.text)
            for entries in (
                self.user_stated_context,
                self.user_preferences,
                self.open_topics,
            )
            for entry in entries
        )
        if total_chars > MAX_SUMMARY_TEXT_CHARS:
            raise ValueError(
                f"summary text exceeds {MAX_SUMMARY_TEXT_CHARS} characters"
            )
        return self


class _InputMessage(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True, strict=True)

    id: int = Field(gt=0)
    sender_type: int = Field(alias="senderType", ge=1, le=2)
    content: str


class ContextSummaryService:
    """Summarizes bounded conversation data without advancing on invalid output."""

    def __init__(self, model: Any):
        self.model = model

    async def summarize(
        self,
        previous_summary: ContextSummary | dict[str, Any] | None,
        messages: list[dict[str, Any]],
        user_id: str | int | None = None,
    ) -> ContextSummary:
        # user_id is accepted for the later request-governance integration. It is
        # deliberately excluded from the model prompt and summary payload.
        del user_id

        validated_previous = self._parse_previous_summary(previous_summary)
        validated_messages = self._validate_messages(messages)
        allowed_source_ids = self._source_ids(validated_previous)
        allowed_source_ids.update(message.id for message in validated_messages)

        prompt_data = {
            "previousSummary": (
                validated_previous.model_dump(by_alias=True) if validated_previous else None
            ),
            "messages": [message.model_dump(by_alias=True) for message in validated_messages],
        }
        response = await self.model.ainvoke(
            [
                SystemMessage(content=CONTEXT_SUMMARY_SYSTEM_PROMPT),
                HumanMessage(content=json.dumps(prompt_data, ensure_ascii=False)),
            ]
        )
        return self._parse_output(getattr(response, "content", None), allowed_source_ids)

    @staticmethod
    def _parse_previous_summary(
        previous_summary: ContextSummary | dict[str, Any] | None,
    ) -> ContextSummary | None:
        if previous_summary is None:
            return None
        try:
            if isinstance(previous_summary, ContextSummary):
                previous_summary = previous_summary.model_dump(by_alias=True)
            return ContextSummary.model_validate(previous_summary)
        except ValidationError as error:
            raise ContextSummaryInputError("previous summary is invalid") from error

    @staticmethod
    def _validate_messages(messages: list[dict[str, Any]]) -> list[_InputMessage]:
        if not isinstance(messages, list):
            raise ContextSummaryInputError("messages must be a list")
        if len(messages) > MAX_INPUT_MESSAGES:
            raise ContextSummaryInputError(
                f"messages exceed {MAX_INPUT_MESSAGES} items"
            )
        try:
            validated = [_InputMessage.model_validate(message) for message in messages]
        except ValidationError as error:
            raise ContextSummaryInputError("message input is invalid") from error

        message_ids = [message.id for message in validated]
        if len(set(message_ids)) != len(message_ids):
            raise ContextSummaryInputError("message IDs must be unique")
        if sum(len(message.content) for message in validated) > MAX_INPUT_TEXT_CHARS:
            raise ContextSummaryInputError(
                f"message content exceeds {MAX_INPUT_TEXT_CHARS} characters"
            )
        return validated

    @staticmethod
    def _source_ids(summary: ContextSummary | None) -> set[int]:
        if summary is None:
            return set()
        return {
            source_id
            for entries in (
                summary.user_stated_context,
                summary.user_preferences,
                summary.open_topics,
            )
            for entry in entries
            for source_id in entry.source_message_ids
        }

    @staticmethod
    def _parse_output(content: Any, allowed_source_ids: set[int]) -> ContextSummary:
        if not isinstance(content, str):
            raise ContextSummaryOutputError("model response must contain JSON text")
        try:
            decoded = json.loads(content)
            summary = ContextSummary.model_validate(decoded)
        except (json.JSONDecodeError, ValidationError, TypeError) as error:
            raise ContextSummaryOutputError("model response is not a valid summary") from error

        unknown_source_ids = {
            source_id
            for source_id in ContextSummaryService._source_ids(summary)
            if source_id not in allowed_source_ids
        }
        if unknown_source_ids:
            raise ContextSummaryOutputError(
                "summary references messages outside the supplied session context"
            )
        return summary
