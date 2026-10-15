package com.amha.service.impl;

import com.amha.agent.RealChatAgent;
import com.amha.common.BusinessException;
import com.amha.dto.ContextSummaryAgentRequest;
import com.amha.dto.ContextSummaryAgentResponse;
import com.amha.dto.ContextSummaryMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("${app.outbox.enabled:true} && "
        + "${amha.chat.context-summary.worker-enabled:false} && "
        + "${amha.chat.context-summary.agent-enabled:false}")
public class ContextSummaryOutboxWorker {
    private static final String DEFAULT_STREAM_KEY = "amha:outbox:context-summary";
    private static final String CONSUMER_GROUP = "context-summary-workers";
    private final String consumerName = "context-summary-worker-" + UUID.randomUUID();

    private final ContextSummaryOutboxService outboxService;
    private final RealChatAgent realChatAgent;
    private final ContextSummaryMetrics metrics;
    private final StringRedisTemplate redisTemplate;
    private final RedisConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;
    private final OutboxPendingReclaimer pendingReclaimer = new OutboxPendingReclaimer("context-summary");

    @Value("${amha.chat.context-summary.stream-key:amha:outbox:context-summary}")
    private String streamKey = DEFAULT_STREAM_KEY;
    @Value("${amha.chat.context-summary.batch-max-messages:40}")
    private int batchMaxMessages = 40;
    @Value("${amha.chat.context-summary.batch-max-chars:16000}")
    private int batchMaxChars = 16_000;
    @Value("${amha.chat.context-summary.processing-lease:PT90S}")
    private Duration processingLease = Duration.ofSeconds(90);
    @Value("${app.outbox.pending-reclaim-min-idle:PT3M}")
    private Duration pendingReclaimMinIdle = Duration.ofMinutes(3);
    @Value("${app.outbox.pending-reclaim-batch-size:10}")
    private int pendingReclaimBatchSize = 10;

    private volatile StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private volatile Subscription subscription;

    @Scheduled(fixedDelayString = "${amha.chat.context-summary.dispatch-interval-ms:2000}")
    public void publishOneReadyTask() {
        Long outboxId = outboxService.claimNextForDispatch();
        if (outboxId == null) return;
        try {
            RecordId recordId = redisTemplate.opsForStream().add(streamKey,
                    Map.of("outboxId", outboxId.toString()));
            if (recordId == null) {
                log.warn("Context summary task {} was not published; database lease will retry it", outboxId);
            }
        } catch (Exception error) {
            // The database dispatch lease expires and makes the authoritative task publishable again.
            log.warn("Context summary task {} could not be published to Redis Stream", outboxId);
        }
    }

    @Scheduled(initialDelay = 1000, fixedDelay = 5000)
    public void ensureStreamConsumer() {
        if (subscription != null && subscription.isActive()) return;
        try {
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(streamKey))) {
                // A sentinel still follows the outboxId-only payload contract and is ACKed as an empty task.
                redisTemplate.opsForStream().add(streamKey, Map.of("outboxId", "0"));
            }
            try {
                redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0-0"), CONSUMER_GROUP);
            } catch (Exception error) {
                if (error.getMessage() == null || !error.getMessage().contains("BUSYGROUP")) throw error;
            }
            StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String,
                    MapRecord<String, String, String>> options =
                    StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                            .pollTimeout(Duration.ofSeconds(1)).batchSize(10).build();
            container = StreamMessageListenerContainer.create(connectionFactory, options);
            startListening(container);
        } catch (Exception error) {
            log.warn("Redis Stream consumer for context summaries is not ready; initialization will retry");
        }
    }

    @Scheduled(fixedDelayString = "${app.outbox.pending-replay-interval-ms:15000}")
    public void replayOwnPendingEntries() {
        if (subscription == null || !subscription.isActive()) return;
        try {
            int availableSlots = pendingReclaimer.availableSlots(10);
            if (availableSlots == 0) return;
            StreamOperations<String, String, String> operations = redisTemplate.opsForStream();
            var pending = operations.read(consumerIdentity(),
                    org.springframework.data.redis.connection.stream.StreamReadOptions.empty()
                            .count(availableSlots),
                    StreamOffset.create(streamKey, ReadOffset.from("0-0")));
            if (pending != null) pendingReclaimer.submitPending(pending, availableSlots, this::onMessage);
        } catch (Exception error) {
            log.warn("Unable to replay pending context summary stream entries");
        }
    }

    @Scheduled(fixedDelayString = "${app.outbox.pending-reclaim-interval-ms:30000}")
    public void reclaimStalePendingEntries() {
        if (subscription == null || !subscription.isActive()) return;
        try {
            int reclaimed = pendingReclaimer.reclaim(streamKey, CONSUMER_GROUP, consumerName,
                    redisTemplate.opsForStream(), pendingReclaimMinIdle, pendingReclaimBatchSize,
                    this::onMessage);
            if (reclaimed > 0) log.info("Reclaimed {} stale context summary stream entries", reclaimed);
        } catch (Exception error) {
            log.warn("Unable to reclaim stale context summary stream entries");
        }
    }

    @PostConstruct
    void validateConfiguration() {
        if (streamKey == null || streamKey.isBlank()) {
            throw new IllegalStateException("context summary stream key must not be blank");
        }
        if (batchMaxMessages < 1 || batchMaxMessages > 40 || batchMaxChars < 1 || batchMaxChars > 16_000) {
            throw new IllegalStateException("context summary batch limits exceed their supported bounds");
        }
        if (processingLease == null || pendingReclaimMinIdle.compareTo(processingLease) < 0) {
            throw new IllegalStateException("pending reclaim idle time must be at least the processing lease");
        }
        if (pendingReclaimBatchSize < 1 || pendingReclaimBatchSize > 100) {
            throw new IllegalStateException("app.outbox.pending-reclaim-batch-size must be between 1 and 100");
        }
    }

    @PreDestroy
    void stopListenerContainer() {
        StreamMessageListenerContainer<String, MapRecord<String, String, String>> current = container;
        if (current != null) {
            try {
                if (current.isRunning()) current.stop();
            } catch (Exception error) {
                log.warn("Unable to stop the context summary stream listener cleanly");
            }
        }
        subscription = null;
        pendingReclaimer.close();
    }

    Consumer consumerIdentity() {
        return Consumer.from(CONSUMER_GROUP, consumerName);
    }

    void startListening(StreamMessageListenerContainer<String, MapRecord<String, String, String>> listenerContainer) {
        container = listenerContainer;
        subscription = listenerContainer.receive(consumerIdentity(),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()), this::onMessage);
        listenerContainer.start();
    }

    synchronized void onMessage(MapRecord<String, String, String> record) {
        String rawId = record.getValue().get("outboxId");
        if (rawId == null) {
            acknowledge(record);
            return;
        }
        final Long outboxId;
        try {
            outboxId = Long.parseLong(rawId);
            if (outboxId <= 0) {
                acknowledge(record);
                return;
            }
        } catch (NumberFormatException error) {
            acknowledge(record);
            return;
        }

        ContextSummaryOutboxService.ContextSummaryClaim claim;
        try {
            claim = outboxService.claimForProcessing(outboxId);
        } catch (Exception error) {
            log.warn("Context summary task {} could not be claimed; stream record remains pending", outboxId);
            return;
        }
        if (claim == null) {
            acknowledge(record);
            return;
        }

        boolean acknowledged = processTask(claim);
        if (acknowledged) acknowledge(record);
    }

    private boolean processTask(ContextSummaryOutboxService.ContextSummaryClaim claim) {
        try {
            ContextSummaryAgentRequest request = outboxService.loadRequest(
                    claim, batchMaxMessages, batchMaxChars);
            long startedAt = System.nanoTime();
            ContextSummaryAgentResponse response = realChatAgent.summarizeContext(request);
            outboxService.complete(claim, response);
            long durationMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            metrics.recordCompleted(durationMillis, requestCharCount(request), responseCharCount(response));
            return true;
        } catch (Exception error) {
            String errorCode = classify(error);
            log.warn("Context summary task {} failed with {}", claim.outboxId(), errorCode);
            try {
                outboxService.fail(claim, errorCode);
                return true;
            } catch (Exception persistenceError) {
                log.warn("Context summary task {} retry state could not be persisted; stream record remains pending",
                        claim.outboxId());
                return false;
            }
        }
    }

    private int requestCharCount(ContextSummaryAgentRequest request) {
        int messageChars = request.messages().stream()
                .map(ContextSummaryMessage::content)
                .filter(content -> content != null)
                .mapToInt(content -> content.codePointCount(0, content.length()))
                .sum();
        try {
            String previousSummary = objectMapper.writeValueAsString(request.previousSummary());
            return messageChars + previousSummary.codePointCount(0, previousSummary.length());
        } catch (Exception ignored) {
            return messageChars;
        }
    }

    private int responseCharCount(ContextSummaryAgentResponse response) {
        try {
            String summary = objectMapper.writeValueAsString(response.summary());
            return summary.codePointCount(0, summary.length());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String classify(Exception error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException) return "AGENT_TIMEOUT";
            if (cause instanceof WebClientResponseException responseError) {
                return "AGENT_HTTP_" + responseError.getStatusCode().value();
            }
        }
        if (error instanceof BusinessException businessException) {
            return switch (businessException.getCode()) {
                case "CONTEXT_TOO_LARGE" -> "CONTEXT_TOO_LARGE";
                case "CONTEXT_UNAVAILABLE" -> "CONTEXT_UNAVAILABLE";
                case "CONTEXT_INVALID_RESPONSE" -> "CONTEXT_INVALID_RESPONSE";
                default -> "SUMMARY_PROCESSING_FAILED";
            };
        }
        if (error instanceof IllegalStateException || error instanceof IllegalArgumentException) {
            return "AGENT_SUMMARY_INVALID";
        }
        return "AGENT_SUMMARY_FAILED";
    }

    private void acknowledge(MapRecord<String, String, String> record) {
        try {
            redisTemplate.opsForStream().acknowledge(CONSUMER_GROUP, record);
        } catch (Exception error) {
            log.warn("Unable to acknowledge context summary stream record {}", record.getId());
        }
    }
}
