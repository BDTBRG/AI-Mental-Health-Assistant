package com.amha.agent;

import com.amha.dto.ContextSummaryAgentRequest;
import com.amha.dto.ContextSummaryAgentResponse;
import com.amha.entity.ConsultationMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;

/**
 * 真实 AI Agent 实现：通过 HTTP 调用 Python FastAPI Agent 服务。
 */
@Slf4j
@Component
@Primary
public class RealChatAgent implements ChatAgent {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String internalToken;
    @Value("${agent.chat-timeout-ms:270000}")
    private long chatTimeoutMillis = 270_000;
    @Value("${agent.context-summary-timeout-ms:65000}")
    private long contextSummaryTimeoutMillis = 65_000;

    public RealChatAgent(@Value("${agent.base-url:http://localhost:5000}") String agentBaseUrl,
                         @Value("${agent.internal-token:}") String internalToken) {
        this.webClient = WebClient.builder()
                .baseUrl(agentBaseUrl)
                .build();
        this.objectMapper = new ObjectMapper();
        this.internalToken = internalToken;
    }

    @PostConstruct
    void validateAgentTimeouts() {
        if (chatTimeoutMillis <= 0 || chatTimeoutMillis >= 300_000) {
            throw new IllegalStateException("agent.chat-timeout-ms must be positive and shorter than 300 seconds");
        }
        if (contextSummaryTimeoutMillis <= 0 || contextSummaryTimeoutMillis > 65_000) {
            throw new IllegalStateException("agent.context-summary-timeout-ms must be between 1 and 65000");
        }
    }

    @Override
    public Flux<String> streamResponse(Long sessionId, String userMessage,
                                       List<ConsultationMessage> history,
                                       Map<String, Object> contextSummary,
                                       Map<String, Object> context) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (history != null) {
            for (ConsultationMessage msg : history) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("senderType", msg.getSenderType());
                m.put("content", msg.getContent());
                messages.add(m);
            }
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("sessionId", String.valueOf(sessionId));
        requestBody.put("messages", messages);
        requestBody.put("userMessage", userMessage);
        requestBody.put("contextSummary", contextSummary == null ? Map.of() : contextSummary);
        requestBody.put("context", context == null ? Map.of() : context);

        return Flux.defer(() -> {
                    long deadlineNanos = System.nanoTime() + Duration.ofMillis(chatTimeoutMillis).toNanos();
                    Mono<Long> firstTimeout = Mono.delay(remainingUntil(deadlineNanos));
                    return webClient.post()
                            .uri("/api/agent/v2/chat")
                            .header("X-Agent-Token", internalToken)
                            .bodyValue(requestBody)
                            .retrieve()
                            .bodyToFlux(String.class)
                            .timeout(firstTimeout, ignored -> Mono.delay(remainingUntil(deadlineNanos)))
                            .filter(line -> !line.trim().isEmpty())
                            .map(line -> {
                                try {
                                    JsonNode node = objectMapper.readTree(line);
                                    if ("200".equals(node.path("code").asText())) {
                                        JsonNode data = node.get("data");
                                        if (data != null && data.has("content")) {
                                            return data.get("content").asText();
                                        }
                                        return "";
                                    }
                                    throw new IllegalStateException("Agent 回复暂时不可用");
                                } catch (Exception error) {
                                    log.warn("Failed to parse or process agent response line ({})",
                                            error.getClass().getSimpleName());
                                    throw new IllegalStateException("Agent 回复暂时不可用", error);
                                }
                            })
                            .filter(content -> !content.isEmpty());
                })
                .onErrorMap(TimeoutException.class,
                        error -> new IllegalStateException("Agent 对话请求超时", error));
    }

    /** Calls the non-streaming internal summarizer without logging request or response content. */
    public ContextSummaryAgentResponse summarizeContext(ContextSummaryAgentRequest request) {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException("Agent internal token is not configured");
        }
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            throw new IllegalArgumentException("Context summary request is empty");
        }
        ContextSummaryAgentResponse response = webClient.post()
                .uri("/api/agent/v2/context-summary")
                .header("X-Agent-Token", internalToken)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ContextSummaryAgentResponse.class)
                .block(Duration.ofMillis(contextSummaryTimeoutMillis));
        validateContextSummaryResponse(request, response);
        return response;
    }

    private static Duration remainingUntil(long deadlineNanos) {
        return Duration.ofNanos(Math.max(1L, deadlineNanos - System.nanoTime()));
    }

    private static void validateContextSummaryResponse(ContextSummaryAgentRequest request,
                                                       ContextSummaryAgentResponse response) {
        if (response == null || response.summary() == null
                || !Objects.equals(request.promptVersion(), response.promptVersion())) {
            throw new IllegalStateException("Agent 摘要响应契约无效");
        }
        Long expectedWatermark = request.messages().get(request.messages().size() - 1).id();
        if (expectedWatermark == null || !expectedWatermark.equals(response.coveredThroughMessageId())) {
            throw new IllegalStateException("Agent 摘要水位与消息批次不匹配");
        }

        Set<Long> allowedSources = new HashSet<>();
        if (request.previousSummary() != null) {
            validateSummarySchema(request.previousSummary(), allowedSources, true);
        }
        for (var message : request.messages()) {
            if (message.id() == null || message.id() <= 0) {
                throw new IllegalStateException("Agent 摘要请求消息 ID 无效");
            }
            allowedSources.add(message.id());
        }
        validateSummarySchema(response.summary(), allowedSources, false);
    }

    private static void validateSummarySchema(Map<?, ?> summary, Set<Long> allowedSources,
                                              boolean collectSources) {
        Set<String> fields = Set.of("userStatedContext", "userPreferences", "openTopics");
        if (summary.size() != fields.size() || !summary.keySet().equals(fields)) {
            throw new IllegalStateException("Agent 摘要字段无效");
        }
        int totalTextChars = 0;
        for (String field : fields) {
            Object value = summary.get(field);
            if (!(value instanceof List<?> entries) || entries.size() > 8) {
                throw new IllegalStateException("Agent 摘要条目数量无效");
            }
            for (Object item : entries) {
                if (!(item instanceof Map<?, ?> entry)
                        || entry.size() != 2
                        || !entry.keySet().equals(Set.of("text", "sourceMessageIds"))) {
                    throw new IllegalStateException("Agent 摘要条目结构无效");
                }
                Object textValue = entry.get("text");
                Object sourceValue = entry.get("sourceMessageIds");
                if (!(textValue instanceof String text) || text.isBlank()
                        || text.codePointCount(0, text.length()) > 240
                        || !(sourceValue instanceof List<?> sourceIds)
                        || sourceIds.isEmpty() || sourceIds.size() > 5) {
                    throw new IllegalStateException("Agent 摘要内容或来源无效");
                }
                totalTextChars += text.codePointCount(0, text.length());
                if (totalTextChars > 1_200) {
                    throw new IllegalStateException("Agent 摘要总长度超限");
                }
                Set<Long> uniqueIds = new HashSet<>();
                for (Object sourceIdValue : sourceIds) {
                    if (!(sourceIdValue instanceof Number number)) {
                        throw new IllegalStateException("Agent 摘要来源 ID 无效");
                    }
                    long sourceId = exactLong(number);
                    if (sourceId <= 0 || !uniqueIds.add(sourceId)) {
                        throw new IllegalStateException("Agent 摘要来源 ID 无效");
                    }
                    if (collectSources) allowedSources.add(sourceId);
                    else if (!allowedSources.contains(sourceId)) {
                        throw new IllegalStateException("Agent 摘要引用了未知消息");
                    }
                }
            }
        }
    }

    private static long exactLong(Number number) {
        try {
            if (number instanceof BigDecimal decimal) return decimal.longValueExact();
            if (number instanceof BigInteger integer) return integer.longValueExact();
            if (number instanceof Double || number instanceof Float) {
                return BigDecimal.valueOf(number.doubleValue()).longValueExact();
            }
            return number.longValue();
        } catch (ArithmeticException | NumberFormatException error) {
            throw new IllegalStateException("Agent 摘要来源 ID 无效");
        }
    }

    @Override
    public Map<String, Object> analyzeEmotion(Long sessionId,
                                               List<ConsultationMessage> messages,
                                               Long userId) {
        List<Map<String, Object>> msgList = new ArrayList<>();
        if (messages != null) {
            for (ConsultationMessage msg : messages) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("senderType", msg.getSenderType());
                m.put("content", msg.getContent());
                msgList.add(m);
            }
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("messages", msgList);
        requestBody.put("userId", userId == null ? null : userId.toString());

        try {
            String response = webClient.post()
                    .uri("/api/agent/emotion/analyze")
                    .header("X-Agent-Token", internalToken)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));

            if (response != null) {
                JsonNode node = objectMapper.readTree(response);
                if ("200".equals(node.get("code").asText())) {
                    JsonNode data = node.get("data");
                    if (data != null) {
                        return objectMapper.convertValue(data, Map.class);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Emotion analysis failed: {}", e.getMessage());
        }

        // Fallback
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("primaryEmotion", "暂不可判断");
        fallback.put("emotionScore", null);
        fallback.put("riskLevel", null);
        fallback.put("riskStatus", "unknown");
        fallback.put("analysisStatus", "unavailable");
        fallback.put("isNegative", null);
        fallback.put("riskDescription", "情绪分析暂时不可用，暂不能判断风险");
        fallback.put("suggestion", "当前分析不可用；如果你正处于紧急危险中，请联系身边可信任的人或当地紧急服务。");
        fallback.put("improvementSuggestions", List.of());
        return fallback;
    }

    /**
     * 调用 Python Agent 进行日记情感分析。
     */
    public Mono<String> analyzeDiary(Map<String, Object> diaryData) {
        return webClient.post()
                .uri("/api/agent/diary/analyze")
                .header("X-Agent-Token", internalToken)
                .bodyValue(diaryData)
                .retrieve()
                .bodyToMono(String.class)
                .map(response -> {
                    try {
                        JsonNode node = objectMapper.readTree(response);
                        if ("200".equals(node.get("code").asText())) {
                            JsonNode data = node.get("data");
                            if (data != null && data.has("analysis")) {
                                return data.get("analysis").asText();
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse diary analysis response ({} characters)", response.length());
                    }
                    return "";
                })
                .onErrorResume(e -> {
                    log.error("Diary analysis failed: {}", e.getMessage());
                    return Mono.just("");
                });
    }

    public void syncKnowledgeArticle(Long articleId, String title, String content, boolean published) {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException("Agent internal token is not configured");
        }
        if (!published) {
            webClient.delete()
                    .uri("/api/agent/rag/article/{articleId}", articleId)
                    .header("X-Agent-Token", internalToken)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(30));
            return;
        }

        Map<String, Object> article = new LinkedHashMap<>();
        article.put("id", articleId);
        article.put("title", title == null ? "" : title);
        article.put("content", content == null ? "" : content);
        webClient.put()
                .uri("/api/agent/rag/article/{articleId}", articleId)
                .header("X-Agent-Token", internalToken)
                .bodyValue(article)
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(90));
    }

    public Map<String, Object> rebuildKnowledgeIndex(List<Map<String, Object>> articles) {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException("Agent internal token is not configured");
        }
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("articles", articles == null ? List.of() : articles);
        String response = webClient.post()
                .uri("/api/agent/rag/rebuild")
                .header("X-Agent-Token", internalToken)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofMinutes(15));
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("Agent 未返回知识索引重建结果");
        }
        try {
            JsonNode node = objectMapper.readTree(response);
            if (!"200".equals(node.path("code").asText())) {
                throw new IllegalStateException("Agent 知识索引重建失败");
            }
            JsonNode data = node.get("data");
            if (data == null || !data.isObject()) {
                throw new IllegalStateException("Agent 未返回已验证的索引信息");
            }
            return objectMapper.convertValue(data, new TypeReference<Map<String, Object>>() {});
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Agent 知识索引重建响应无效", error);
        }
    }

    @Override
    public void purgeSessionState(Long sessionId) {
        purgeSessionState(sessionId, false);
    }

    @Override
    public void purgeSessionState(Long sessionId, boolean permanentlyDeleted) {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException("Agent internal token is not configured");
        }
        if (permanentlyDeleted) {
            webClient.delete()
                    .uri("/api/agent/v2/sessions/{sessionId}", sessionId)
                    .header("X-Agent-Token", internalToken)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofMinutes(10));
        } else {
            webClient.post()
                    .uri("/api/agent/v2/sessions/{sessionId}/checkpoint/purge", sessionId)
                    .header("X-Agent-Token", internalToken)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofMinutes(10));
        }
    }
}
