package com.amha.service.impl;

import com.amha.config.ChatContextSummaryProperties;
import com.amha.dto.ChatContextPreparation;
import com.amha.entity.ConsultationChatTurn;
import com.amha.entity.ConsultationContextSummary;
import com.amha.entity.ConsultationContextSummaryOutbox;
import com.amha.entity.ConsultationMessage;
import com.amha.mapper.ConsultationContextSummaryMapper;
import com.amha.mapper.ConsultationContextSummaryOutboxMapper;
import com.amha.mapper.ConsultationMessageMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ChatContextAssembler {
    private static final int QUERY_WINDOW_SIZE = 41;
    private static final TypeReference<Map<String, Object>> SUMMARY_TYPE = new TypeReference<>() {};
    private static final List<String> SUMMARY_FIELDS = List.of(
            "userStatedContext", "userPreferences", "openTopics");

    private final ConsultationMessageMapper messageMapper;
    private final ConsultationContextSummaryMapper summaryMapper;
    private final ConsultationContextSummaryOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final ChatContextSummaryProperties properties;
    private boolean outboxEnabled;

    @Autowired
    public ChatContextAssembler(ConsultationMessageMapper messageMapper,
                                ConsultationContextSummaryMapper summaryMapper,
                                ConsultationContextSummaryOutboxMapper outboxMapper,
                                ObjectMapper objectMapper,
                                ChatContextSummaryProperties properties,
                                @Value("${app.outbox.enabled:true}") boolean outboxEnabled) {
        this.messageMapper = messageMapper;
        this.summaryMapper = summaryMapper;
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.outboxEnabled = outboxEnabled;
    }

    public ChatContextPreparation prepare(Long sessionId, Long userId, String userMessage,
                                          boolean currentMessagePersisted) {
        return prepare(sessionId, userId, userMessage, currentMessagePersisted, Map.of());
    }

    public ChatContextPreparation prepare(Long sessionId, Long userId, String userMessage,
                                          boolean currentMessagePersisted,
                                          Map<String, Object> agentContext) {
        if (sessionId == null || userId == null || userMessage == null) {
            return unavailable(null, null, 0L);
        }

        ConsultationContextSummary summary = summaryMapper.selectBySessionAndUser(sessionId, userId);
        long watermark = summary == null ? 0L : value(summary.getCoveredThroughMessageId());
        Long maxMessageId = messageMapper.selectMaxMessageIdForOwner(sessionId, userId);
        if (watermark < 0L || (maxMessageId == null && watermark > 0L)
                || (maxMessageId != null && watermark > maxMessageId)) {
            return unavailable(null, null, watermark);
        }

        Map<String, Object> contextSummary;
        int summaryChars;
        try {
            contextSummary = parseSummary(summary, watermark);
            summaryChars = serializedChars(contextSummary);
        } catch (Exception ignored) {
            return unavailable(null, null, watermark);
        }
        if (summaryChars > properties.getMaxContextChars()) {
            return unavailable(null, null, watermark);
        }

        List<ConsultationMessage> queried = messageMapper.selectLatestAfterWatermarkForOwner(
                sessionId, userId, watermark, QUERY_WINDOW_SIZE);
        List<ConsultationMessage> history = new ArrayList<>();
        if (queried != null) {
            for (ConsultationMessage message : queried) {
                if (message == null || message.getId() == null || message.getId() <= watermark
                        || !Objects.equals(message.getSessionId(), sessionId)) continue;
                history.add(message);
            }
        }
        history.sort(Comparator.comparing(ConsultationMessage::getId));

        Long fetchedLatestMessageId = history.isEmpty()
                ? null : history.get(history.size() - 1).getId();
        Long expectedFetchedLatestMessageId = maxMessageId != null && maxMessageId > watermark
                ? maxMessageId : null;
        if (!Objects.equals(fetchedLatestMessageId, expectedFetchedLatestMessageId)) {
            return unavailable(contextSummary, null, watermark);
        }

        if (currentMessagePersisted) {
            if (history.isEmpty()) return unavailable(contextSummary, null, watermark);
            ConsultationMessage last = history.get(history.size() - 1);
            if (!Integer.valueOf(1).equals(last.getSenderType())
                    || !Objects.equals(userMessage, last.getContent())) {
                return unavailable(contextSummary, null, watermark);
            }
            history.remove(history.size() - 1);
        }

        int currentChars = codePoints(userMessage);
        if (currentChars > properties.getMaxSingleMessageChars()) {
            return tooLarge(contextSummary, null, watermark);
        }

        int keepHistory = Math.max(0, properties.getRecentMessagesToKeep() - 1);
        int recentStart = Math.max(0, history.size() - keepHistory);
        List<ConsultationMessage> recentHistory = history.subList(recentStart, history.size());
        Long target = summaryTarget(history, watermark, keepHistory);
        if (recentHistory.stream().anyMatch(message -> codePoints(message.getContent())
                > properties.getMaxSingleMessageChars())) {
            return tooLarge(contextSummary, target, watermark);
        }

        long recentChars = summaryChars + currentChars + recentHistory.stream()
                .mapToLong(message -> codePoints(message.getContent())).sum();
        if (recentChars > properties.getMaxContextChars()) {
            return tooLarge(contextSummary, target, watermark);
        }
        if (!fitsAgentBody(sessionId, userMessage, recentHistory, contextSummary, agentContext)) {
            return unavailable(contextSummary, target, watermark);
        }

        long totalChars = summaryChars + currentChars + history.stream()
                .mapToLong(message -> codePoints(message.getContent())).sum();
        boolean overBudget = history.size() > properties.getMaxHistoryMessages()
                || totalChars > properties.getMaxContextChars()
                || !fitsAgentBody(sessionId, userMessage, history, contextSummary, agentContext);
        if (!overBudget) {
            Long throughMessageId = history.isEmpty() ? (watermark > 0 ? watermark : null)
                    : history.get(history.size() - 1).getId();
            return new ChatContextPreparation(ChatContextPreparation.Status.READY,
                    history, contextSummary, target, throughMessageId, 0L, maxMessageId);
        }

        if (target == null || target <= watermark) {
            return tooLarge(contextSummary, target, watermark);
        }
        ConsultationContextSummaryOutbox task = outboxMapper.selectBySessionAndUser(sessionId, userId);
        if (!properties.isPreflightEnabled() || !properties.isWorkerEnabled()
                || !properties.isAgentEnabled() || !outboxEnabled
                || (task != null && "DEAD".equals(task.getStatus()))) {
            return unavailable(contextSummary, target, watermark);
        }
        return new ChatContextPreparation(ChatContextPreparation.Status.SUMMARY_PENDING,
                recentHistory, contextSummary, target, watermark, properties.getRetryAfterMs());
    }

    @PostConstruct
    void validateConfiguration() {
        if (properties.getMaxHistoryMessages() < 1
                || properties.getMaxHistoryMessages() >= QUERY_WINDOW_SIZE) {
            throw new IllegalStateException("max-history-messages must be between 1 and 40");
        }
        if (properties.getRecentMessagesToKeep() < 1
                || properties.getRecentMessagesToKeep() > properties.getMaxHistoryMessages() + 1) {
            throw new IllegalStateException(
                    "recent-messages-to-keep must be between 1 and max-history-messages + 1");
        }
        if (properties.getMaxContextChars() <= 0 || properties.getMaxSingleMessageChars() <= 0
                || properties.getMaxSingleMessageChars() > 8_000
                || properties.getMaxAgentRequestBytes() <= 0 || properties.getRetryAfterMs() < 0) {
            throw new IllegalStateException("chat context summary size and retry limits must be positive and bounded");
        }
    }

    private Map<String, Object> parseSummary(ConsultationContextSummary summary, long watermark) throws Exception {
        if (summary == null || watermark == 0L) return Map.of();
        if (summary.getSummaryJson() == null || summary.getSummaryJson().isBlank()
                || !"context-summary-v1".equals(summary.getPromptVersion())) {
            throw new IllegalArgumentException("Stored summary contract is incomplete");
        }
        Map<String, Object> parsed = objectMapper.readValue(summary.getSummaryJson(), SUMMARY_TYPE);
        if (parsed.size() != SUMMARY_FIELDS.size() || !parsed.keySet().containsAll(SUMMARY_FIELDS)) {
            throw new IllegalArgumentException("Stored summary schema is invalid");
        }
        for (String field : SUMMARY_FIELDS) {
            if (!(parsed.get(field) instanceof List<?> items) || items.size() > 8) {
                throw new IllegalArgumentException("Stored summary schema is invalid");
            }
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> entry)
                        || !entry.keySet().equals(java.util.Set.of("text", "sourceMessageIds"))
                        || !(entry.get("text") instanceof String text)
                        || text.isBlank() || codePoints(text) > 240
                        || !(entry.get("sourceMessageIds") instanceof List<?> sourceIds)
                        || sourceIds.isEmpty() || sourceIds.size() > 5) {
                    throw new IllegalArgumentException("Stored summary schema is invalid");
                }
                for (Object sourceId : sourceIds) {
                    if (!(sourceId instanceof Number number) || number.longValue() <= 0
                            || number.longValue() > watermark) {
                        throw new IllegalArgumentException("Stored summary source is invalid");
                    }
                }
            }
        }
        return Map.copyOf(parsed);
    }

    private boolean fitsAgentBody(Long sessionId, String userMessage, List<ConsultationMessage> history,
                                  Map<String, Object> contextSummary, Map<String, Object> agentContext) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", String.valueOf(sessionId));
        body.put("userMessage", userMessage);
        body.put("history", history.stream().map(message -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("senderType", message.getSenderType());
            item.put("content", message.getContent() == null ? "" : message.getContent());
            return item;
        }).toList());
        body.put("contextSummary", contextSummary);
        body.put("context", agentContext == null ? Map.of() : agentContext);
        try {
            int safetyMargin = Math.min(1_024, properties.getMaxAgentRequestBytes() / 8);
            return objectMapper.writeValueAsBytes(body).length
                    <= properties.getMaxAgentRequestBytes() - safetyMargin;
        } catch (Exception ignored) {
            return false;
        }
    }

    private int serializedChars(Map<String, Object> contextSummary) throws Exception {
        return codePoints(objectMapper.writeValueAsString(contextSummary));
    }

    private Long summaryTarget(List<ConsultationMessage> history, long watermark, int keepHistory) {
        int targetIndex = history.size() - keepHistory - 1;
        if (targetIndex < 0) return null;
        Long target = history.get(targetIndex).getId();
        return target != null && target > watermark ? target : null;
    }

    private ChatContextPreparation unavailable(Map<String, Object> summary, Long target, Long watermark) {
        return new ChatContextPreparation(ChatContextPreparation.Status.SUMMARY_UNAVAILABLE,
                List.of(), summary, target, watermark, properties.getRetryAfterMs());
    }

    private ChatContextPreparation tooLarge(Map<String, Object> summary, Long target, Long watermark) {
        return new ChatContextPreparation(ChatContextPreparation.Status.WINDOW_TOO_LARGE,
                List.of(), summary, target, watermark, 0L);
    }

    private static int codePoints(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    private static long value(Long number) {
        return number == null ? 0L : number;
    }
}
