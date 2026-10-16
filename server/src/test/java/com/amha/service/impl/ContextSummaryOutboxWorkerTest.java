package com.amha.service.impl;

import com.amha.agent.RealChatAgent;
import com.amha.dto.ContextSummaryAgentRequest;
import com.amha.dto.ContextSummaryAgentResponse;
import com.amha.dto.ContextSummaryMessage;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextSummaryOutboxWorkerTest {
    private static final String STREAM = "amha:outbox:context-summary";
    private static final String GROUP = "context-summary-workers";

    @Test
    void dispatcherPublishesOnlyTheOutboxId() {
        ContextSummaryOutboxService service = mock(ContextSummaryOutboxService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, String, String> operations = mock(StreamOperations.class);
        doReturn(operations).when(redis).opsForStream();
        when(service.claimNextForDispatch()).thenReturn(61L);
        when(operations.add(STREAM, Map.of("outboxId", "61"))).thenReturn(RecordId.of("2-0"));

        worker(service, mock(RealChatAgent.class), mock(ContextSummaryMetrics.class), redis)
                .publishOneReadyTask();

        verify(operations).add(STREAM, Map.of("outboxId", "61"));
    }

    @Test
    void claimsBeforeCallingAgentAndAcknowledgesOnlyAfterSummaryIsCommitted() {
        ContextSummaryOutboxService service = mock(ContextSummaryOutboxService.class);
        RealChatAgent agent = mock(RealChatAgent.class);
        ContextSummaryMetrics metrics = mock(ContextSummaryMetrics.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, String, String> operations = mock(StreamOperations.class);
        doReturn(operations).when(redis).opsForStream();

        var claim = claim();
        var request = request();
        var response = response();
        when(service.claimForProcessing(51L)).thenReturn(claim);
        when(service.loadRequest(claim, 40, 16_000)).thenReturn(request);
        when(agent.summarizeContext(request)).thenReturn(response);

        ContextSummaryOutboxWorker worker = worker(service, agent, metrics, redis);
        MapRecord<String, String, String> record = record(Map.of("outboxId", "51"));
        worker.onMessage(record);

        var ordered = inOrder(service, agent, operations);
        ordered.verify(service).claimForProcessing(51L);
        ordered.verify(service).loadRequest(claim, 40, 16_000);
        ordered.verify(agent).summarizeContext(request);
        ordered.verify(service).complete(claim, response);
        ordered.verify(operations).acknowledge(GROUP, record);
        verify(metrics).recordCompleted(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void expiredOrDeletedTaskIsAcknowledgedWithoutCallingAgent() {
        ContextSummaryOutboxService service = mock(ContextSummaryOutboxService.class);
        RealChatAgent agent = mock(RealChatAgent.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, String, String> operations = mock(StreamOperations.class);
        doReturn(operations).when(redis).opsForStream();
        when(service.claimForProcessing(52L)).thenReturn(null);

        worker(service, agent, mock(ContextSummaryMetrics.class), redis)
                .onMessage(record(Map.of("outboxId", "52")));

        verify(agent, never()).summarizeContext(org.mockito.ArgumentMatchers.any());
        verify(operations).acknowledge(eq(GROUP), org.mockito.ArgumentMatchers.any(MapRecord.class));
    }

    @Test
    void duplicateStreamDeliveryDoesNotCallAgentTwice() {
        ContextSummaryOutboxService service = mock(ContextSummaryOutboxService.class);
        RealChatAgent agent = mock(RealChatAgent.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, String, String> operations = mock(StreamOperations.class);
        doReturn(operations).when(redis).opsForStream();
        var claim = claim();
        var request = request();
        var response = response();
        when(service.claimForProcessing(53L)).thenReturn(claim).thenReturn(null);
        when(service.loadRequest(claim, 40, 16_000)).thenReturn(request);
        when(agent.summarizeContext(request)).thenReturn(response);

        ContextSummaryOutboxWorker worker = worker(service, agent, mock(ContextSummaryMetrics.class), redis);
        MapRecord<String, String, String> record = record(Map.of("outboxId", "53"));
        worker.onMessage(record);
        worker.onMessage(record);

        verify(agent, times(1)).summarizeContext(request);
        verify(service, times(1)).complete(claim, response);
        verify(operations, times(2)).acknowledge(eq(GROUP), eq(record));
    }

    @Test
    void agentFailureIsAcknowledgedOnlyAfterDatabaseRetryStateIsWritten() {
        ContextSummaryOutboxService service = mock(ContextSummaryOutboxService.class);
        RealChatAgent agent = mock(RealChatAgent.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, String, String> operations = mock(StreamOperations.class);
        doReturn(operations).when(redis).opsForStream();
        var claim = claim();
        when(service.claimForProcessing(53L)).thenReturn(claim);
        when(service.loadRequest(claim, 40, 16_000)).thenReturn(request());
        when(agent.summarizeContext(request())).thenThrow(new IllegalStateException("sensitive response body"));

        MapRecord<String, String, String> record = record(Map.of("outboxId", "53"));
        worker(service, agent, mock(ContextSummaryMetrics.class), redis).onMessage(record);

        var ordered = inOrder(service, operations);
        ordered.verify(service).fail(eq(claim), anyString());
        ordered.verify(operations).acknowledge(GROUP, record);
    }

    @Test
    void failedRetryPersistenceLeavesTheStreamRecordPending() {
        ContextSummaryOutboxService service = mock(ContextSummaryOutboxService.class);
        RealChatAgent agent = mock(RealChatAgent.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, String, String> operations = mock(StreamOperations.class);
        doReturn(operations).when(redis).opsForStream();
        var claim = claim();
        when(service.claimForProcessing(54L)).thenReturn(claim);
        when(service.loadRequest(claim, 40, 16_000)).thenReturn(request());
        when(agent.summarizeContext(request())).thenThrow(new IllegalStateException("upstream failed"));
        doThrow(new IllegalStateException("database unavailable"))
                .when(service).fail(eq(claim), anyString());

        MapRecord<String, String, String> record = record(Map.of("outboxId", "54"));
        worker(service, agent, mock(ContextSummaryMetrics.class), redis).onMessage(record);

        verify(operations, never()).acknowledge(GROUP, record);
    }

    @Test
    void nonPositiveBootstrapRecordIsAcknowledgedWithoutClaimingWork() {
        ContextSummaryOutboxService service = mock(ContextSummaryOutboxService.class);
        RealChatAgent agent = mock(RealChatAgent.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, String, String> operations = mock(StreamOperations.class);
        doReturn(operations).when(redis).opsForStream();
        MapRecord<String, String, String> record = record(Map.of("outboxId", "0"));
        when(service.claimForProcessing(0L)).thenReturn(null);

        worker(service, agent, mock(ContextSummaryMetrics.class), redis).onMessage(record);

        verify(service, never()).claimForProcessing(0L);
        verify(agent, never()).summarizeContext(org.mockito.ArgumentMatchers.any());
        verify(operations).acknowledge(GROUP, record);
    }

    private static ContextSummaryOutboxWorker worker(ContextSummaryOutboxService service,
                                                       RealChatAgent agent,
                                                       ContextSummaryMetrics metrics,
                                                       StringRedisTemplate redis) {
        return new ContextSummaryOutboxWorker(service, agent, metrics, redis,
                mock(RedisConnectionFactory.class), new ObjectMapper());
    }

    private static MapRecord<String, String, String> record(Map<String, String> payload) {
        return MapRecord.create(STREAM, payload).withId(RecordId.of("1-0"));
    }

    private static ContextSummaryOutboxService.ContextSummaryClaim claim() {
        return new ContextSummaryOutboxService.ContextSummaryClaim(
                53L, 17L, 29L, 41L, 0L, "processing-lease");
    }

    private static ContextSummaryAgentRequest request() {
        return new ContextSummaryAgentRequest(17L, 29L, null,
                List.of(new ContextSummaryMessage(41L, 1, "recent work stress")),
                "context-summary-v1", "correlation-53");
    }

    private static ContextSummaryAgentResponse response() {
        return new ContextSummaryAgentResponse(Map.of(
                "userStatedContext", List.of(Map.of("text", "work stress", "sourceMessageIds", List.of(41))),
                "userPreferences", List.of(),
                "openTopics", List.of()), 41L, "context-summary-v1");
    }
}
