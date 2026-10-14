package com.amha.service.impl;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Bounded, cursor-based reclamation of stale Redis Stream pending entries. */
final class OutboxPendingReclaimer implements AutoCloseable {
    private static final int PROCESSING_QUEUE_CAPACITY = 100;

    private final ThreadPoolExecutor processingExecutor;
    private final Semaphore processingSlots = new Semaphore(PROCESSING_QUEUE_CAPACITY + 1);
    private final Set<String> inFlightRecordIds = ConcurrentHashMap.newKeySet();
    private String cursorAfterId;

    OutboxPendingReclaimer(String workerName) {
        this.processingExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(PROCESSING_QUEUE_CAPACITY), runnable -> {
                    Thread thread = new Thread(runnable, workerName + "-outbox-recovery");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    synchronized int availableSlots(int requestedMaximum) {
        if (processingExecutor.isShutdown() || requestedMaximum <= 0) return 0;
        return Math.min(requestedMaximum, processingSlots.availablePermits());
    }

    synchronized int submitPending(
            List<MapRecord<String, String, String>> pending,
            int requestedMaximum,
            Consumer<MapRecord<String, String, String>> messageHandler) {
        int available = availableSlots(requestedMaximum);
        int submitted = 0;
        for (MapRecord<String, String, String> record : pending) {
            if (submitted >= available) break;
            if (submit(record, messageHandler)) submitted++;
        }
        return submitted;
    }

    synchronized int reclaim(
            String streamKey,
            String groupName,
            String consumerName,
            StreamOperations<String, String, String> operations,
            Duration minIdle,
            int requestedMaximum,
            Consumer<MapRecord<String, String, String>> messageHandler) {
        int reservedSlots = reserveSlots(requestedMaximum);
        if (reservedSlots == 0) return 0;
        int transferredReservations = 0;
        try {
            Range<String> scanRange = cursorAfterId == null
                    ? Range.unbounded()
                    : Range.rightUnbounded(Range.Bound.exclusive(cursorAfterId));
            PendingMessages pending = operations.pending(streamKey, groupName, scanRange, reservedSlots);
            if (pending == null || pending.isEmpty()) {
                // Wrap on the next scheduled run so pending records before the cursor are visited.
                cursorAfterId = null;
                return 0;
            }

            List<RecordId> staleIds = new ArrayList<>(Math.min(pending.size(), reservedSlots));
            int scanned = 0;
            for (PendingMessage message : pending) {
                if (scanned == reservedSlots) break;
                cursorAfterId = message.getIdAsString();
                scanned++;
                if (message.getElapsedTimeSinceLastDelivery().compareTo(minIdle) >= 0) {
                    staleIds.add(message.getId());
                }
            }
            if (staleIds.isEmpty()) return 0;

            // Redis checks minIdle again atomically, so a delivery refreshed after XPENDING is not stolen.
            List<MapRecord<String, String, String>> claimed = operations.claim(
                    streamKey, groupName, consumerName, minIdle, staleIds.toArray(RecordId[]::new));
            if (claimed == null || claimed.isEmpty()) return 0;
            int callbacks = Math.min(claimed.size(), reservedSlots);
            for (int index = 0; index < callbacks; index++) {
                transferredReservations++;
                submitReserved(claimed.get(index), messageHandler);
            }
            return claimed.size();
        } finally {
            int unusedReservations = reservedSlots - transferredReservations;
            if (unusedReservations > 0) processingSlots.release(unusedReservations);
        }
    }

    private int reserveSlots(int requestedMaximum) {
        if (processingExecutor.isShutdown() || requestedMaximum <= 0) return 0;
        int reserved = 0;
        int maximum = Math.min(requestedMaximum, PROCESSING_QUEUE_CAPACITY + 1);
        while (reserved < maximum && processingSlots.tryAcquire()) reserved++;
        return reserved;
    }

    private boolean submit(MapRecord<String, String, String> record,
                           Consumer<MapRecord<String, String, String>> messageHandler) {
        if (!processingSlots.tryAcquire()) return false;
        try {
            return submitReserved(record, messageHandler);
        } catch (RejectedExecutionException rejected) {
            return false;
        }
    }

    private boolean submitReserved(MapRecord<String, String, String> record,
                                   Consumer<MapRecord<String, String, String>> messageHandler) {
        String id = record.getId().getValue();
        if (!inFlightRecordIds.add(id)) {
            processingSlots.release();
            return false;
        }
        try {
            processingExecutor.execute(() -> {
                try {
                    messageHandler.accept(record);
                } finally {
                    inFlightRecordIds.remove(id);
                    processingSlots.release();
                }
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            inFlightRecordIds.remove(id);
            processingSlots.release();
            throw rejected;
        }
    }

    @Override
    public synchronized void close() {
        processingExecutor.shutdown();
        try {
            if (!processingExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                processingExecutor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            processingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
