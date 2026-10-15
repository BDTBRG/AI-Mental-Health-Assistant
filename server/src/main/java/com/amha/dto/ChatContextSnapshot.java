package com.amha.dto;

import com.amha.entity.ConsultationMessage;

import java.util.List;
import java.util.Map;

public record ChatContextSnapshot(
        List<ConsultationMessage> history,
        Map<String, Object> contextSummary,
        Long throughMessageId,
        Long observedLatestMessageId
) {
    public ChatContextSnapshot {
        history = history == null ? List.of() : List.copyOf(history);
        contextSummary = contextSummary == null ? Map.of() : Map.copyOf(contextSummary);
    }
}
