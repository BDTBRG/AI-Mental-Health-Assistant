package com.amha.service.impl;

import com.amha.agent.RealChatAgent;
import com.amha.entity.KnowledgeArticle;
import com.amha.entity.KnowledgeIndexOutbox;
import com.amha.mapper.KnowledgeArticleMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeIndexOutboxWorker {
    private static final String STREAM_KEY = "amha:outbox:knowledge-index";
    private static final String CONSUMER_GROUP = "knowledge-index-workers";
    private final String consumerName = "knowledge-index-worker-" + UUID.randomUUID();

    private final KnowledgeIndexOutboxService outboxService;
    private final KnowledgeArticleMapper articleMapper;
    private final RealChatAgent realChatAgent;
    private final StringRedisTemplate redisTemplate;
    private final RedisConnectionFactory connectionFactory;

    private final OutboxPendingReclaimer pendingReclaimer = new OutboxPendingReclaimer("knowledge-index");
    @Value("${app.outbox.pending-reclaim-min-idle:PT3M}")
    private Duration pendingReclaimMinIdle = Duration.ofMinutes(3);
    @Value("${app.outbox.pending-reclaim-batch-size:10}")
    private int pendingReclaimBatchSize = 10;

    private volatile StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private volatile Subscription subscription;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:2000}")
    public void publishOneReadyTask() {
        KnowledgeIndexOutbox task = outboxService.claimNextForDispatch();
        if (task == null) return;
        try {
            RecordId recordId = redisTemplate.opsForStream().add(STREAM_KEY, Map.of(
                    "taskId", task.getId().toString(),
                    "dispatchToken", task.getLeaseToken()));
            if (recordId == null) outboxService.returnToPending(task.getId(), task.getLeaseToken());
        } catch (Exception error) {
            outboxService.returnToPending(task.getId(), task.getLeaseToken());
            log.warn("Unable to publish knowledge index task {} to Redis Stream", task.getId());
        }
    }

    @Scheduled(initialDelay = 1000, fixedDelay = 5000)
    public void ensureStreamConsumer() {
        if (subscription != null && subscription.isActive()) return;
        try {
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(STREAM_KEY))) {
                redisTemplate.opsForStream().add(STREAM_KEY, Map.of("bootstrap", "true"));
            }
            try {
                redisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0-0"), CONSUMER_GROUP);
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
            log.warn("Redis Stream consumer for knowledge indexing is not ready; initialization will retry");
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
                    org.springframework.data.redis.connection.stream.StreamReadOptions.empty().count(availableSlots),
                    StreamOffset.create(STREAM_KEY, ReadOffset.from("0-0")));
            if (pending != null) pendingReclaimer.submitPending(pending, availableSlots, this::onMessage);
        } catch (Exception error) {
            log.warn("Unable to replay pending knowledge index stream entries");
        }
    }

    /** Recover only deliveries that have remained idle longer than the processing lease. */
    @Scheduled(fixedDelayString = "${app.outbox.pending-reclaim-interval-ms:30000}")
    public void reclaimStalePendingEntries() {
        if (subscription == null || !subscription.isActive()) return;
        try {
            int reclaimed = pendingReclaimer.reclaim(STREAM_KEY, CONSUMER_GROUP, consumerName,
                    redisTemplate.opsForStream(), pendingReclaimMinIdle, pendingReclaimBatchSize,
                    this::onMessage);
            if (reclaimed > 0) {
                log.info("Reclaimed {} stale knowledge index stream entries", reclaimed);
            }
        } catch (Exception error) {
            log.warn("Unable to reclaim stale knowledge index stream entries", error);
        }
    }

    @PostConstruct
    void validatePendingReclaimConfiguration() {
        if (pendingReclaimMinIdle.compareTo(Duration.ofMinutes(3)) < 0) {
            throw new IllegalStateException("app.outbox.pending-reclaim-min-idle must be at least PT3M");
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
                log.warn("Unable to stop the knowledge index stream listener cleanly", error);
            }
        }
        subscription = null;
        pendingReclaimer.close();
    }

    String consumerName() {
        return consumerName;
    }

    Consumer consumerIdentity() {
        return Consumer.from(CONSUMER_GROUP, consumerName);
    }

    void startListening(StreamMessageListenerContainer<String, MapRecord<String, String, String>> listenerContainer) {
        container = listenerContainer;
        subscription = listenerContainer.receive(consumerIdentity(),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()), this::onMessage);
        listenerContainer.start();
    }

    private synchronized void onMessage(MapRecord<String, String, String> record) {
        boolean acknowledge = false;
        String taskId = record.getValue().get("taskId");
        String dispatchToken = record.getValue().get("dispatchToken");
        if (taskId == null || dispatchToken == null) {
            acknowledge = true;
        } else {
            try {
                KnowledgeIndexOutbox claimed = outboxService.claimForProcessing(
                        Long.parseLong(taskId), dispatchToken);
                if (claimed == null) {
                    acknowledge = true;
                } else {
                    processTask(claimed);
                    acknowledge = true;
                }
            } catch (Exception error) {
                log.warn("Knowledge index task {} will remain pending", taskId);
            }
        }
        if (acknowledge) {
            try {
                redisTemplate.opsForStream().acknowledge(CONSUMER_GROUP, record);
            } catch (Exception error) {
                log.warn("Unable to acknowledge knowledge index record {}", record.getId());
            }
        }
    }

    private void processTask(KnowledgeIndexOutbox task) {
        try {
            KnowledgeArticle article = articleMapper.selectById(task.getArticleId());
            boolean published = article != null && Integer.valueOf(1).equals(article.getStatus());
            realChatAgent.syncKnowledgeArticle(task.getArticleId(),
                    published ? article.getTitle() : null,
                    published ? article.getContent() : null,
                    published);
            outboxService.complete(task);
        } catch (Exception error) {
            log.warn("Knowledge index task {} failed on attempt {}", task.getId(), task.getAttempts());
            outboxService.fail(task, "AGENT_INDEX_SYNC_FAILED");
        }
    }
}
