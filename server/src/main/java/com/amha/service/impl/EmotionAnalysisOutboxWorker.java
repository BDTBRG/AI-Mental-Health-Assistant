package com.amha.service.impl;

import com.amha.agent.RealChatAgent;
import com.amha.entity.EmotionAnalysisOutbox;
import com.amha.entity.EmotionDiary;
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
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class EmotionAnalysisOutboxWorker {
    private static final String STREAM_KEY = "amha:outbox:emotion-analysis";
    private static final String CONSUMER_GROUP = "emotion-analysis-workers";

    private final EmotionAnalysisOutboxService outboxService;
    private final RealChatAgent realChatAgent;
    private final StringRedisTemplate redisTemplate;
    private final RedisConnectionFactory connectionFactory;

    private final String consumerName = "emotion-analysis-worker-" + UUID.randomUUID();
    private final OutboxPendingReclaimer pendingReclaimer = new OutboxPendingReclaimer("emotion-analysis");
    @Value("${app.outbox.pending-reclaim-min-idle:PT3M}")
    private Duration pendingReclaimMinIdle = Duration.ofMinutes(3);
    @Value("${app.outbox.pending-reclaim-batch-size:10}")
    private int pendingReclaimBatchSize = 10;
    private volatile StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private volatile Subscription subscription;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:2000}")
    public void publishOneReadyTask() {
        EmotionAnalysisOutbox task = outboxService.claimNextForDispatch();
        if (task == null) return;
        try {
            RecordId recordId = redisTemplate.opsForStream().add(STREAM_KEY, Map.of(
                    "taskId", task.getId().toString(),
                    "dispatchToken", task.getLeaseToken()));
            if (recordId == null) outboxService.returnToPending(task.getId(), task.getLeaseToken());
        } catch (Exception error) {
            outboxService.returnToPending(task.getId(), task.getLeaseToken());
            log.warn("Unable to publish emotion analysis task {} to Redis Stream", task.getId());
        }
    }

    @Scheduled(initialDelay = 1000, fixedDelay = 5000)
    public void ensureStreamConsumer() {
        if (subscription != null && subscription.isActive()) return;
        try {
            Boolean streamExists = redisTemplate.hasKey(STREAM_KEY);
            if (!Boolean.TRUE.equals(streamExists)) {
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
                            .pollTimeout(Duration.ofSeconds(1))
                            .batchSize(10)
                            .build();
            container = StreamMessageListenerContainer.create(connectionFactory, options);
            startListening(container);
        } catch (Exception error) {
            log.warn("Redis Stream consumer is not ready; the outbox will retry initialization");
        }
    }

    /** Re-read this instance's unacknowledged entries after a callback or process interruption. */
    @Scheduled(fixedDelayString = "${app.outbox.pending-replay-interval-ms:15000}")
    public void replayOwnPendingEntries() {
        if (subscription == null || !subscription.isActive()) return;
        try {
            int availableSlots = pendingReclaimer.availableSlots(10);
            if (availableSlots == 0) return;
            StreamOperations<String, String, String> streamOperations = redisTemplate.opsForStream();
            var pending = streamOperations.read(
                    consumerIdentity(),
                    org.springframework.data.redis.connection.stream.StreamReadOptions.empty().count(availableSlots),
                    StreamOffset.create(STREAM_KEY, ReadOffset.from("0-0")));
            if (pending != null) pendingReclaimer.submitPending(pending, availableSlots, this::onMessage);
        } catch (Exception error) {
            log.warn("Unable to replay pending Redis Stream entries");
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
                log.info("Reclaimed {} stale emotion analysis stream entries", reclaimed);
            }
        } catch (Exception error) {
            log.warn("Unable to reclaim stale emotion analysis stream entries", error);
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
                log.warn("Unable to stop the emotion analysis stream listener cleanly", error);
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
        String taskIdValue = record.getValue().get("taskId");
        String dispatchToken = record.getValue().get("dispatchToken");
        if (taskIdValue == null || dispatchToken == null) {
            acknowledge = true;
        } else {
            try {
                EmotionAnalysisOutbox task = outboxService.claimForProcessing(
                        Long.parseLong(taskIdValue), dispatchToken);
                if (task == null) {
                    // A duplicate, expired, or already completed delivery is safe to acknowledge.
                    acknowledge = true;
                } else {
                    processTask(task);
                    acknowledge = true;
                }
            } catch (Exception error) {
                log.warn("Emotion analysis stream record {} will remain pending", record.getId());
            }
        }

        if (acknowledge) {
            try {
                redisTemplate.opsForStream().acknowledge(CONSUMER_GROUP, record);
            } catch (Exception error) {
                log.warn("Unable to acknowledge emotion analysis stream record {}", record.getId());
            }
        }
    }

    void processTask(EmotionAnalysisOutbox task) {
        try {
            EmotionDiary diary = outboxService.loadDiaryForDispatch(task);
            if (diary == null) {
                outboxService.fail(task, "DIARY_NOT_FOUND");
                return;
            }
            Map<String, Object> diaryData = new LinkedHashMap<>();
            diaryData.put("userId", diary.getUserId() == null ? null : diary.getUserId().toString());
            diaryData.put("dominantEmotion", diary.getDominantEmotion());
            diaryData.put("moodScore", diary.getMoodScore());
            diaryData.put("emotionTriggers", diary.getEmotionTriggers());
            diaryData.put("diaryContent", diary.getDiaryContent());
            diaryData.put("sleepQuality", diary.getSleepQuality() == null ? "" : diary.getSleepQuality());
            diaryData.put("stressLevel", diary.getStressLevel() == null ? "" : diary.getStressLevel());

            String analysis = realChatAgent.analyzeDiary(diaryData).block(Duration.ofSeconds(45));
            if (analysis == null || analysis.isBlank() || analysis.startsWith("[AI分析生成失败:")) {
                outboxService.fail(task, "AGENT_ANALYSIS_FAILED");
                return;
            }
            outboxService.complete(task, analysis);
        } catch (Exception error) {
            log.warn("Diary analysis task {} failed on attempt {}", task.getId(), task.getAttempts());
            outboxService.fail(task, "AGENT_REQUEST_FAILED");
        }
    }
}
