package com.amha.dto;

import java.util.Map;

public record ContextSummaryAgentResponse(
        Map<String, Object> summary,
        Long coveredThroughMessageId,
        String promptVersion
) {
}
