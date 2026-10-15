package com.amha.dto;

import com.amha.common.ChatContextPreparationException;
import com.amha.entity.ConsultationMessage;

import java.util.List;
import java.util.Map;

public record ChatContextPreparation(
        Status status,
        List<ConsultationMessage> history,
        Map<String, Object> contextSummary,
        Long summaryTargetThroughMessageId,
        Long throughMessageId,
        long retryAfterMs,
        Long observedLatestMessageId
) {
    public enum Status {
        READY,
        SUMMARY_PENDING,
        SUMMARY_UNAVAILABLE,
        WINDOW_TOO_LARGE
    }

    public ChatContextPreparation {
        history = history == null ? List.of() : List.copyOf(history);
        contextSummary = contextSummary == null ? Map.of() : Map.copyOf(contextSummary);
        retryAfterMs = Math.max(0L, retryAfterMs);
    }

    public ChatContextPreparation(Status status, List<ConsultationMessage> history,
                                  Map<String, Object> contextSummary,
                                  Long summaryTargetThroughMessageId, Long throughMessageId,
                                  long retryAfterMs) {
        this(status, history, contextSummary, summaryTargetThroughMessageId,
                throughMessageId, retryAfterMs, null);
    }

    public ChatContextSnapshot toSnapshot() {
        if (status != Status.READY) {
            throw new IllegalStateException("Only ready chat context can be used for an Agent request");
        }
        return new ChatContextSnapshot(history, contextSummary, throughMessageId, observedLatestMessageId);
    }

    public ChatContextPreparationException toException(String clientRequestId) {
        return switch (status) {
            case SUMMARY_PENDING -> new ChatContextPreparationException(
                    "CONTEXT_SUMMARY_PENDING", "正在整理较早的对话，请稍后重试",
                    clientRequestId, true, retryAfterMs);
            case SUMMARY_UNAVAILABLE -> new ChatContextPreparationException(
                    "CONTEXT_SUMMARY_UNAVAILABLE", "当前暂时无法整理较早的对话，请稍后重试",
                    clientRequestId, true, retryAfterMs);
            case WINDOW_TOO_LARGE -> new ChatContextPreparationException(
                    "CONTEXT_WINDOW_TOO_LARGE", "近期对话内容超过安全处理上限，请新建会话后继续",
                    clientRequestId, false, 0L);
            case READY -> throw new IllegalStateException("Ready chat context has no preparation error");
        };
    }
}
