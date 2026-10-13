package com.amha.service.impl;

import com.amha.agent.RealChatAgent;
import com.amha.entity.KnowledgeArticle;
import com.amha.entity.KnowledgeIndexOutbox;
import com.amha.mapper.KnowledgeArticleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeIndexOutboxWorker {
    private static final String STREAM_KEY = "amha:outbox:knowledge-index";
    private static final String CONSUMER_GROUP = "knowledge-index-workers";
    private static final String CONSUMER_NAME = "knowledge-index-worker";

    private final KnowledgeIndexOutboxService outboxService;
    private final KnowledgeArticleMapper articleMapper;
    private final RealChatAgent realChatAgent;
    private final StringRedisTemplate redisTemplate;
    private final RedisConnectionFactory connectionFactory;

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
            subscription = container.receive(
                    Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
                    StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()), this::onMessage);
            container.start();
        } catch (Exception error) {
            log.warn("Redis Stream consumer for knowledge indexing is not ready; initialization will retry");
        }
    }

    @Scheduled(fixedDelayString = "${app.outbox.pending-replay-interval-ms:15000}")
    public void replayOwnPendingEntries() {
        if (subscription == null || !subscription.isActive()) return;
        try {
            StreamOperations<String, String, String> operations = redisTemplate.opsForStream();
            var pending = operations.read(Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
                    org.springframework.data.redis.connection.stream.StreamReadOptions.empty().count(10),
                    StreamOffset.create(STREAM_KEY, ReadOffset.from("0-0")));
            if (pending != null) pending.forEach(this::onMessage);
        } catch (Exception error) {
            log.warn("Unable to replay pending knowledge index stream entries");
        }
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
