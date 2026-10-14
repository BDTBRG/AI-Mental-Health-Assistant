package com.amha.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPendingReclaimerTest {

    @Test
    @SuppressWarnings("unchecked")
    void claimsOnlyEntriesPastTheIdleThresholdAndForwardsClaimedRecords() throws InterruptedException {
        String stream = "test:outbox";
        String group = "test-workers";
        String consumer = "test-worker-unique-id";
        Duration minIdle = Duration.ofMinutes(3);
        StreamOperations<String, String, String> operations = mock(StreamOperations.class);
        MapRecord<String, String, String> claimedRecord = MapRecord.create(stream,
                Map.of("taskId", "42", "dispatchToken", "lease-42")).withId(RecordId.of("2-0"));
        when(operations.pending(eq(stream), eq(group), any(Range.class), anyLong()))
                .thenReturn(new PendingMessages(group, Range.unbounded(), List.of(
                        pending("1-0", group, "old-consumer", Duration.ofMinutes(2).plusSeconds(59)),
                        pending("2-0", group, "dead-consumer", minIdle),
                        pending("3-0", group, "dead-consumer", Duration.ofMinutes(8)))));
        when(operations.claim(eq(stream), eq(group), eq(consumer), eq(minIdle), any(RecordId[].class)))
                .thenReturn(List.of(claimedRecord));

        try (OutboxPendingReclaimer reclaimer = new OutboxPendingReclaimer("test")) {
            AtomicReference<MapRecord<String, String, String>> processed = new AtomicReference<>();
            CountDownLatch callbackCalled = new CountDownLatch(1);
            int reclaimed = reclaimer.reclaim(stream, group, consumer, operations, minIdle, 10,
                    recordValue -> {
                        processed.set(recordValue);
                        callbackCalled.countDown();
                    });

            assertEquals(1, reclaimed);
            assertTrue(callbackCalled.await(2, TimeUnit.SECONDS));
            assertSame(claimedRecord, processed.get());
            verify(operations).claim(eq(stream), eq(group), eq(consumer), eq(minIdle),
                    eq(RecordId.of("2-0")), eq(RecordId.of("3-0")));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void scansPendingEntriesWithAnExclusiveCursorAndWrapsAfterTheLastPage() {
        String stream = "test:outbox";
        String group = "test-workers";
        StreamOperations<String, String, String> operations = mock(StreamOperations.class);
        PendingMessages firstPage = new PendingMessages(group, Range.unbounded(), List.of(
                pending("1-0", group, "active", Duration.ofSeconds(30)),
                pending("2-0", group, "active", Duration.ofSeconds(30))));
        PendingMessages secondPage = new PendingMessages(group, Range.unbounded(), List.of(
                pending("3-0", group, "active", Duration.ofSeconds(30)),
                pending("4-0", group, "active", Duration.ofSeconds(30))));
        PendingMessages emptyPage = new PendingMessages(group, Range.unbounded(), List.of());
        when(operations.pending(eq(stream), eq(group), any(Range.class), anyLong()))
                .thenReturn(firstPage, secondPage, emptyPage, firstPage);

        try (OutboxPendingReclaimer reclaimer = new OutboxPendingReclaimer("test")) {
            for (int i = 0; i < 4; i++) {
                reclaimer.reclaim(stream, group, "recoverer", operations,
                        Duration.ofMinutes(3), 2, ignored -> { });
            }
        }

        org.mockito.ArgumentCaptor<Range<String>> ranges =
                org.mockito.ArgumentCaptor.forClass(Range.class);
        verify(operations, times(4)).pending(eq(stream), eq(group), ranges.capture(), eq(2L));
        assertEquals(Range.unbounded(), ranges.getAllValues().get(0));
        assertEquals(Range.rightUnbounded(Range.Bound.exclusive("2-0")), ranges.getAllValues().get(1));
        assertEquals(Range.rightUnbounded(Range.Bound.exclusive("4-0")), ranges.getAllValues().get(2));
        assertEquals(Range.unbounded(), ranges.getAllValues().get(3));
        verify(operations, never()).claim(any(), any(), any(), any(), any(RecordId[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void closedReclaimerDoesNotScanOrClaimPendingEntries() {
        StreamOperations<String, String, String> operations = mock(StreamOperations.class);
        OutboxPendingReclaimer reclaimer = new OutboxPendingReclaimer("test");
        reclaimer.close();

        assertEquals(0, reclaimer.reclaim("test:outbox", "test-workers", "recoverer", operations,
                Duration.ofMinutes(3), 10, ignored -> { }));

        verify(operations, never()).pending(eq("test:outbox"), eq("test-workers"),
                any(Range.class), anyLong());
        verify(operations, never()).claim(any(), any(), any(), any(), any(RecordId[].class));
    }

    private PendingMessage pending(String id, String group, String consumer, Duration idle) {
        return new PendingMessage(RecordId.of(id), Consumer.from(group, consumer), idle, 1);
    }
}
