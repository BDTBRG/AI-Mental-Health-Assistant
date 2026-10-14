package com.amha.service.impl;

import com.amha.agent.RealChatAgent;
import com.amha.entity.EmotionAnalysisOutbox;
import com.amha.entity.EmotionDiary;
import com.amha.entity.KnowledgeArticle;
import com.amha.entity.KnowledgeIndexOutbox;
import com.amha.mapper.KnowledgeArticleMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxWorkerRecoveryTest {

    @Test
    @SuppressWarnings("unchecked")
    void reclaimedEmotionRecordUsesExistingClaimProcessingAndAckPath() {
        EmotionAnalysisOutboxService service = mock(EmotionAnalysisOutboxService.class);
        RealChatAgent agent = mock(RealChatAgent.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, String, String> operations = mock(StreamOperations.class);
        doReturn(operations).when(redis).opsForStream();
        when(operations.pending(eq("amha:outbox:emotion-analysis"), eq("emotion-analysis-workers"),
                any(Range.class), anyLong())).thenReturn(pending("emotion-analysis-workers", "abandoned"));
        MapRecord<String, String, String> record = MapRecord.create("amha:outbox:emotion-analysis",
                Map.of("taskId", "21", "dispatchToken", "dispatch-21")).withId(RecordId.of("9-0"));
        when(operations.claim(eq("amha:outbox:emotion-analysis"), eq("emotion-analysis-workers"),
                any(String.class), eq(Duration.ofMinutes(3)), any(RecordId[].class)))
                .thenReturn(List.of(record));
        EmotionAnalysisOutbox task = new EmotionAnalysisOutbox();
        task.setId(21L);
        task.setDiaryId(22L);
        task.setLeaseToken("processing-lease-21");
        task.setAttempts(1);
        EmotionDiary diary = new EmotionDiary();
        diary.setUserId(8L);
        diary.setDiaryContent("authorized test diary");
        when(service.claimForProcessing(21L, "dispatch-21")).thenReturn(task);
        when(service.loadDiaryForDispatch(task)).thenReturn(diary);
        when(agent.analyzeDiary(anyMap())).thenReturn(Mono.just("analysis-result"));
        EmotionAnalysisOutboxWorker worker = new EmotionAnalysisOutboxWorker(service, agent, redis, null);
        activate(worker);

        worker.reclaimStalePendingEntries();

        verify(service, timeout(3000)).claimForProcessing(21L, "dispatch-21");
        verify(service, timeout(3000)).complete(task, "analysis-result");
        verify(operations, timeout(3000)).acknowledge("emotion-analysis-workers", record);
        worker.stopListenerContainer();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reclaimedKnowledgeRecordUsesExistingClaimProcessingAndAckPath() {
        KnowledgeIndexOutboxService service = mock(KnowledgeIndexOutboxService.class);
        RealChatAgent agent = mock(RealChatAgent.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, String, String> operations = mock(StreamOperations.class);
        doReturn(operations).when(redis).opsForStream();
        when(operations.pending(eq("amha:outbox:knowledge-index"), eq("knowledge-index-workers"),
                any(Range.class), anyLong())).thenReturn(pending("knowledge-index-workers", "abandoned"));
        MapRecord<String, String, String> record = MapRecord.create("amha:outbox:knowledge-index",
                Map.of("taskId", "31", "dispatchToken", "dispatch-31")).withId(RecordId.of("10-0"));
        when(operations.claim(eq("amha:outbox:knowledge-index"), eq("knowledge-index-workers"),
                any(String.class), eq(Duration.ofMinutes(3)), any(RecordId[].class)))
                .thenReturn(List.of(record));
        KnowledgeIndexOutbox task = new KnowledgeIndexOutbox();
        task.setId(31L);
        task.setArticleId(32L);
        task.setLeaseToken("processing-lease-31");
        task.setAttempts(1);
        KnowledgeArticle article = new KnowledgeArticle();
        article.setTitle("test article");
        article.setContent("test content");
        article.setStatus(1);
        when(service.claimForProcessing(31L, "dispatch-31")).thenReturn(task);
        KnowledgeArticleMapper articleMapper = mock(KnowledgeArticleMapper.class);
        when(articleMapper.selectById(32L)).thenReturn(article);
        org.mockito.Mockito.doNothing().when(agent).syncKnowledgeArticle(32L,
                "test article", "test content", true);
        KnowledgeIndexOutboxWorker worker = new KnowledgeIndexOutboxWorker(
                service, articleMapper, agent, redis, null);
        activate(worker);

        worker.reclaimStalePendingEntries();

        verify(service, timeout(3000)).claimForProcessing(31L, "dispatch-31");
        verify(agent, timeout(3000)).syncKnowledgeArticle(32L, "test article", "test content", true);
        verify(service, timeout(3000)).complete(task);
        verify(operations, timeout(3000)).acknowledge("knowledge-index-workers", record);
        worker.stopListenerContainer();
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleRecoverySchedulerDoesNotWaitForTheExistingMessageHandler() throws Exception {
        EmotionAnalysisOutboxService service = mock(EmotionAnalysisOutboxService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, String, String> operations = mock(StreamOperations.class);
        doReturn(operations).when(redis).opsForStream();
        when(operations.pending(eq("amha:outbox:emotion-analysis"), eq("emotion-analysis-workers"),
                any(Range.class), anyLong())).thenReturn(pending("emotion-analysis-workers", "abandoned"));
        MapRecord<String, String, String> record = MapRecord.create("amha:outbox:emotion-analysis",
                Map.of("taskId", "41", "dispatchToken", "dispatch-41")).withId(RecordId.of("11-0"));
        when(operations.claim(eq("amha:outbox:emotion-analysis"), eq("emotion-analysis-workers"),
                any(String.class), eq(Duration.ofMinutes(3)), any(RecordId[].class)))
                .thenReturn(List.of(record));
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch continueHandler = new CountDownLatch(1);
        when(service.claimForProcessing(41L, "dispatch-41")).thenAnswer(invocation -> {
            handlerEntered.countDown();
            assertTrue(continueHandler.await(3, TimeUnit.SECONDS));
            return null;
        });
        EmotionAnalysisOutboxWorker worker = new EmotionAnalysisOutboxWorker(
                service, mock(RealChatAgent.class), redis, null);
        activate(worker);
        var schedulerCaller = Executors.newSingleThreadExecutor();

        try {
            var scheduledCall = schedulerCaller.submit(worker::reclaimStalePendingEntries);
            assertTrue(handlerEntered.await(2, TimeUnit.SECONDS));
            assertDoesNotThrow(() -> scheduledCall.get(500, TimeUnit.MILLISECONDS));
        } finally {
            continueHandler.countDown();
            schedulerCaller.shutdownNow();
            worker.stopListenerContainer();
        }

        verify(operations, timeout(3000)).acknowledge("emotion-analysis-workers", record);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ownPendingReplaySchedulerAlsoQueuesTheExistingMessageHandler() throws Exception {
        EmotionAnalysisOutboxService service = mock(EmotionAnalysisOutboxService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, String, String> operations = mock(StreamOperations.class);
        doReturn(operations).when(redis).opsForStream();
        MapRecord<String, String, String> record = MapRecord.create("amha:outbox:emotion-analysis",
                Map.of("taskId", "42", "dispatchToken", "dispatch-42")).withId(RecordId.of("12-0"));
        when(operations.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(record));
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch continueHandler = new CountDownLatch(1);
        when(service.claimForProcessing(42L, "dispatch-42")).thenAnswer(invocation -> {
            handlerEntered.countDown();
            assertTrue(continueHandler.await(3, TimeUnit.SECONDS));
            return null;
        });
        EmotionAnalysisOutboxWorker worker = new EmotionAnalysisOutboxWorker(
                service, mock(RealChatAgent.class), redis, null);
        activate(worker);
        var schedulerCaller = Executors.newSingleThreadExecutor();

        try {
            var scheduledCall = schedulerCaller.submit(worker::replayOwnPendingEntries);
            assertTrue(handlerEntered.await(2, TimeUnit.SECONDS));
            assertDoesNotThrow(() -> scheduledCall.get(500, TimeUnit.MILLISECONDS));
        } finally {
            continueHandler.countDown();
            schedulerCaller.shutdownNow();
            worker.stopListenerContainer();
        }

        verify(operations, timeout(3000)).acknowledge("emotion-analysis-workers", record);
    }

    @Test
    @SuppressWarnings("unchecked")
    void bothWorkersStopTheirListenerContainersAtShutdown() {
        StreamMessageListenerContainer<String, MapRecord<String, String, String>> emotionContainer =
                mock(StreamMessageListenerContainer.class);
        when(emotionContainer.isRunning()).thenReturn(true);
        EmotionAnalysisOutboxWorker emotionWorker = new EmotionAnalysisOutboxWorker(
                mock(EmotionAnalysisOutboxService.class), mock(RealChatAgent.class), null, null);
        ReflectionTestUtils.setField(emotionWorker, "container", emotionContainer);

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> knowledgeContainer =
                mock(StreamMessageListenerContainer.class);
        when(knowledgeContainer.isRunning()).thenReturn(true);
        KnowledgeIndexOutboxWorker knowledgeWorker = new KnowledgeIndexOutboxWorker(
                mock(KnowledgeIndexOutboxService.class), mock(KnowledgeArticleMapper.class),
                mock(RealChatAgent.class), null, null);
        ReflectionTestUtils.setField(knowledgeWorker, "container", knowledgeContainer);

        emotionWorker.stopListenerContainer();
        knowledgeWorker.stopListenerContainer();

        verify(emotionContainer).stop();
        verify(knowledgeContainer).stop();
    }

    private PendingMessages pending(String group, String consumer) {
        return new PendingMessages(group, Range.unbounded(), List.of(new PendingMessage(
                RecordId.of("2-0"), Consumer.from(group, consumer), Duration.ofMinutes(4), 3)));
    }

    private void activate(EmotionAnalysisOutboxWorker worker) {
        Subscription subscription = mock(Subscription.class);
        when(subscription.isActive()).thenReturn(true);
        ReflectionTestUtils.setField(worker, "subscription", subscription);
    }

    private void activate(KnowledgeIndexOutboxWorker worker) {
        Subscription subscription = mock(Subscription.class);
        when(subscription.isActive()).thenReturn(true);
        ReflectionTestUtils.setField(worker, "subscription", subscription);
    }
}
