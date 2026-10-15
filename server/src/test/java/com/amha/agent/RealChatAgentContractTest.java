package com.amha.agent;

import com.sun.net.httpserver.HttpServer;
import com.amha.dto.ContextSummaryAgentRequest;
import com.amha.dto.ContextSummaryAgentResponse;
import com.amha.dto.ContextSummaryMessage;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealChatAgentContractTest {

    @Test
    void streamResponseRequiresGenerationAwareAgentEndpoint() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> agentToken = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            agentToken.set(exchange.getRequestHeaders().getFirst("X-Agent-Token"));
            if (!"/api/agent/v2/chat".equals(requestPath.get())) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }

            byte[] response = "{\"code\":\"200\",\"data\":{\"content\":\"ok\"}}\n"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        try {
            RealChatAgent chatAgent = new RealChatAgent(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "unit-token");

            List<String> response = chatAgent.streamResponse(
                            17L, "hello", List.of(), Map.of(
                                    "generationToken", "generation-7",
                                    "generationEpoch", 3,
                                    "turnSequence", 17))
                    .collectList()
                    .block(Duration.ofSeconds(5));

            assertEquals(List.of("ok"), response);
            assertEquals("/api/agent/v2/chat", requestPath.get());
            assertTrue(requestBody.get().contains("\"generationToken\":\"generation-7\""));
            assertTrue(requestBody.get().contains("\"generationEpoch\":3"));
            assertTrue(requestBody.get().contains("\"turnSequence\":17"));
            assertEquals("unit-token", agentToken.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void purgeRoutesDistinguishTemporaryRevocationFromPermanentDeletion() throws Exception {
        List<String> requestPaths = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPaths.add(exchange.getRequestURI().getPath());
            byte[] response = "{\"code\":\"200\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        try {
            RealChatAgent chatAgent = new RealChatAgent(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "unit-token");

            chatAgent.purgeSessionState(17L, false);
            chatAgent.purgeSessionState(18L, true);

            assertEquals(List.of(
                    "/api/agent/v2/sessions/17/checkpoint/purge",
                    "/api/agent/v2/sessions/18"), requestPaths);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void summarizeContextUsesTheInternalJsonContractAndValidatesSourcesAndWatermark() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> agentToken = new AtomicReference<>();
        AtomicReference<String> responseBody = new AtomicReference<>(summaryResponse(41L, true));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            agentToken.set(exchange.getRequestHeaders().getFirst("X-Agent-Token"));
            byte[] response = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        try {
            RealChatAgent chatAgent = new RealChatAgent(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "unit-token");
            ContextSummaryAgentRequest request = summaryRequest();

            ContextSummaryAgentResponse result = chatAgent.summarizeContext(request);

            assertEquals("/api/agent/v2/context-summary", requestPath.get());
            assertEquals("unit-token", agentToken.get());
            assertTrue(requestBody.get().contains("\"sessionId\":17"));
            assertTrue(requestBody.get().contains("\"userId\":29"));
            assertTrue(requestBody.get().contains("\"promptVersion\":\"context-summary-v1\""));
            assertTrue(requestBody.get().contains("\"correlationId\":\"summary-correlation-1\""));
            assertTrue(requestBody.get().contains("\"id\":41"));
            assertEquals(41L, result.coveredThroughMessageId());
            assertEquals("context-summary-v1", result.promptVersion());
            assertEquals("近期工作压力较大", ((Map<?, ?>) ((List<?>) result.summary()
                    .get("userStatedContext")).get(0)).get("text"));

            responseBody.set(summaryResponse(41L, false));
            assertThrows(IllegalStateException.class, () -> chatAgent.summarizeContext(request));

            responseBody.set(summaryResponse(41L, true).replace("[41]", "[999]"));
            assertThrows(IllegalStateException.class, () -> chatAgent.summarizeContext(request));

            responseBody.set(summaryResponse(42L, true));
            assertThrows(IllegalStateException.class, () -> chatAgent.summarizeContext(request));
        } finally {
            server.stop(0);
        }
    }

    private static ContextSummaryAgentRequest summaryRequest() {
        return new ContextSummaryAgentRequest(17L, 29L, null,
                List.of(new ContextSummaryMessage(41L, 1, "最近工作压力很大")),
                "context-summary-v1", "summary-correlation-1");
    }

    private static String summaryResponse(long watermark, boolean includeSources) {
        String entry = includeSources
                ? "{\"text\":\"近期工作压力较大\",\"sourceMessageIds\":[41]}"
                : "{\"text\":\"近期工作压力较大\"}";
        return "{\"summary\":{\"userStatedContext\":[" + entry
                + "],\"userPreferences\":[],\"openTopics\":[]},"
                + "\"coveredThroughMessageId\":" + watermark
                + ",\"promptVersion\":\"context-summary-v1\"}";
    }
}
