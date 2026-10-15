package com.amha.dto;

import java.util.List;
import java.util.Map;

public record ContextSummaryAgentRequest(
        Long sessionId,
        Long userId,
        Map<String, Object> previousSummary,
        List<ContextSummaryMessage> messages,
        String promptVersion,
        String correlationId
) {
}
