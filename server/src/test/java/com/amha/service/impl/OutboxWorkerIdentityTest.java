package com.amha.service.impl;

import com.amha.agent.RealChatAgent;
import com.amha.mapper.KnowledgeArticleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.data.redis.stream.Subscription;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxWorkerIdentityTest {

    @Test
    void workerInstancesReceiveUniqueStableConsumerNames() {
        RealChatAgent agent = mock(RealChatAgent.class);
        EmotionAnalysisOutboxWorker firstEmotion = new EmotionAnalysisOutboxWorker(
                mock(EmotionAnalysisOutboxService.class), agent, null, null);
        EmotionAnalysisOutboxWorker secondEmotion = new EmotionAnalysisOutboxWorker(
                mock(EmotionAnalysisOutboxService.class), agent, null, null);
        KnowledgeIndexOutboxWorker firstKnowledge = new KnowledgeIndexOutboxWorker(
                mock(KnowledgeIndexOutboxService.class), mock(KnowledgeArticleMapper.class), agent, null, null);
        KnowledgeIndexOutboxWorker secondKnowledge = new KnowledgeIndexOutboxWorker(
                mock(KnowledgeIndexOutboxService.class), mock(KnowledgeArticleMapper.class), agent, null, null);

        assertNotEquals(firstEmotion.consumerName(), secondEmotion.consumerName());
        assertNotEquals(firstKnowledge.consumerName(), secondKnowledge.consumerName());
        assertTrue(firstEmotion.consumerName().startsWith("emotion-analysis-worker-"));
        assertTrue(firstKnowledge.consumerName().startsWith("knowledge-index-worker-"));
        assertEquals(firstEmotion.consumerName(), firstEmotion.consumerIdentity().getName());
        assertEquals(firstKnowledge.consumerName(), firstKnowledge.consumerIdentity().getName());
    }

    @Test
    @SuppressWarnings("unchecked")
    void emotionListenerAndOwnPendingReplayUseTheSameConsumer() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, String, String> operations = mock(StreamOperations.class);
        doReturn(operations).when(redis).opsForStream();
        when(operations.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of());
        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                mock(StreamMessageListenerContainer.class);
        Subscription subscription = mock(Subscription.class);
        when(subscription.isActive()).thenReturn(true);
        doReturn(subscription).when(container).receive(any(Consumer.class), any(StreamOffset.class),
                any(StreamListener.class));
        EmotionAnalysisOutboxWorker worker = new EmotionAnalysisOutboxWorker(
                mock(EmotionAnalysisOutboxService.class), mock(RealChatAgent.class), redis, null);

        worker.startListening(container);
        worker.replayOwnPendingEntries();

        org.mockito.ArgumentCaptor<Consumer> receiveConsumer =
                org.mockito.ArgumentCaptor.forClass(Consumer.class);
        org.mockito.ArgumentCaptor<Consumer> replayConsumer =
                org.mockito.ArgumentCaptor.forClass(Consumer.class);
        verify(container).receive(receiveConsumer.capture(), any(StreamOffset.class), any(StreamListener.class));
        verify(operations).read(replayConsumer.capture(), any(StreamReadOptions.class), any(StreamOffset.class));
        assertEquals(receiveConsumer.getValue(), replayConsumer.getValue());
        assertEquals(worker.consumerName(), receiveConsumer.getValue().getName());
        worker.stopListenerContainer();
    }

    @Test
    @SuppressWarnings("unchecked")
    void knowledgeListenerAndOwnPendingReplayUseTheSameConsumer() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, String, String> operations = mock(StreamOperations.class);
        doReturn(operations).when(redis).opsForStream();
        when(operations.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of());
        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                mock(StreamMessageListenerContainer.class);
        Subscription subscription = mock(Subscription.class);
        when(subscription.isActive()).thenReturn(true);
        doReturn(subscription).when(container).receive(any(Consumer.class), any(StreamOffset.class),
                any(StreamListener.class));
        KnowledgeIndexOutboxWorker worker = new KnowledgeIndexOutboxWorker(
                mock(KnowledgeIndexOutboxService.class), mock(KnowledgeArticleMapper.class),
                mock(RealChatAgent.class), redis, null);

        worker.startListening(container);
        worker.replayOwnPendingEntries();

        org.mockito.ArgumentCaptor<Consumer> receiveConsumer =
                org.mockito.ArgumentCaptor.forClass(Consumer.class);
        org.mockito.ArgumentCaptor<Consumer> replayConsumer =
                org.mockito.ArgumentCaptor.forClass(Consumer.class);
        verify(container).receive(receiveConsumer.capture(), any(StreamOffset.class), any(StreamListener.class));
        verify(operations).read(replayConsumer.capture(), any(StreamReadOptions.class), any(StreamOffset.class));
        assertEquals(receiveConsumer.getValue(), replayConsumer.getValue());
        assertEquals(worker.consumerName(), receiveConsumer.getValue().getName());
        worker.stopListenerContainer();
    }
}
