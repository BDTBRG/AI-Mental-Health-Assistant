package com.amha.agent;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                            17L, "hello", List.of(), Map.of("generationToken", "generation-7"))
                    .collectList()
                    .block(Duration.ofSeconds(5));

            assertEquals(List.of("ok"), response);
            assertEquals("/api/agent/v2/chat", requestPath.get());
            assertTrue(requestBody.get().contains("\"generationToken\":\"generation-7\""));
            assertEquals("unit-token", agentToken.get());
        } finally {
            server.stop(0);
        }
    }
}
