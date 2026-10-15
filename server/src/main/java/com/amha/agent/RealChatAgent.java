package com.amha.agent;

import com.amha.entity.ConsultationMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

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

    public RealChatAgent(@Value("${agent.base-url:http://localhost:5000}") String agentBaseUrl,
                         @Value("${agent.internal-token:}") String internalToken) {
        this.webClient = WebClient.builder()
                .baseUrl(agentBaseUrl)
                .build();
        this.objectMapper = new ObjectMapper();
        this.internalToken = internalToken;
    }

    @Override
    public Flux<String> streamResponse(Long sessionId, String userMessage,
                                       List<ConsultationMessage> history,
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
        requestBody.put("context", context == null ? Map.of() : context);

        return webClient.post()
                .uri("/api/agent/v2/chat")
                .header("X-Agent-Token", internalToken)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(line -> !line.trim().isEmpty())
                .map(line -> {
                    try {
                        JsonNode node = objectMapper.readTree(line);
                        if ("200".equals(node.get("code").asText())) {
                            JsonNode data = node.get("data");
                            if (data != null && data.has("content")) {
                                return data.get("content").asText();
                            }
                            return "";
                        }
                        String message = node.path("message").asText("Agent 回复暂时不可用");
                        throw new IllegalStateException(message);
                    } catch (Exception e) {
                        log.warn("Failed to parse or process agent response line ({})",
                                e.getClass().getSimpleName());
                        throw new IllegalStateException("Agent 回复暂时不可用", e);
                    }
                })
                .filter(content -> !content.isEmpty());
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
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException("Agent internal token is not configured");
        }
        webClient.delete()
                .uri("/api/agent/sessions/{sessionId}/checkpoint", sessionId)
                .header("X-Agent-Token", internalToken)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(5));
    }
}
