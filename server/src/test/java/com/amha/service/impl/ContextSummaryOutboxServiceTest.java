package com.amha.service.impl;

import com.amha.dto.ContextSummaryAgentRequest;
import com.amha.dto.ContextSummaryAgentResponse;
import com.amha.dto.ContextSummaryMessage;
import com.amha.entity.ConsultationContextSummary;
import com.amha.entity.ConsultationContextSummaryOutbox;
import com.amha.entity.ConsultationMessage;
import com.amha.entity.ConsultationSession;
import com.amha.mapper.ConsultationContextSummaryMapper;
import com.amha.mapper.ConsultationContextSummaryOutboxMapper;
import com.amha.mapper.ConsultationMessageMapper;
import com.amha.mapper.ConsultationSessionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContextSummaryOutboxServiceTest {
    @Mock
    private ConsultationContextSummaryOutboxMapper outboxMapper;
    @Mock
    private ConsultationContextSummaryMapper summaryMapper;
    @Mock
    private ConsultationMessageMapper messageMapper;
    @Mock
    private ConsultationSessionMapper sessionMapper;
    @Mock
    private ContextSummaryMetrics metrics;

    @Test
    void enqueueMergesTheHighestTargetIntoOneSessionTaskUnderSessionLock() {
        ContextSummaryOutboxService service = service();
        ConsultationSession session = session(10L, 7L);
        AtomicReference<ConsultationContextSummaryOutbox> stored = new AtomicReference<>();
        when(sessionMapper.selectByIdForUpdate(10L)).thenReturn(session);
        when(messageMapper.selectMaxMessageId(10L)).thenReturn(100L);
        when(outboxMapper.selectBySessionForUpdate(10L)).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        }).when(outboxMapper).insert(any(ConsultationContextSummaryOutbox.class));

        service.enqueue(10L, 7L, 40L);
        service.enqueue(10L, 7L, 90L);
        service.enqueue(10L, 7L, 60L);

        assertEquals(90L, stored.get().getDesiredThroughMessageId());
        assertEquals("PENDING", stored.get().getStatus());
        verify(outboxMapper).insert(any(ConsultationContextSummaryOutbox.class));
        verify(outboxMapper).updateById(stored.get());
        InOrder locks = inOrder(sessionMapper, outboxMapper);
        locks.verify(sessionMapper).selectByIdForUpdate(10L);
        locks.verify(outboxMapper).selectBySessionForUpdate(10L);
    }

    @Test
    void enqueueDoesNotDeleteHigherTargetStillNotCoveredByExistingSummary() {
        ContextSummaryOutboxService service = service();
        ConsultationContextSummaryOutbox task = task(30L, 10L, 7L, 100L, "PENDING", null);
        when(sessionMapper.selectByIdForUpdate(10L)).thenReturn(session(10L, 7L));
        when(messageMapper.selectMaxMessageId(10L)).thenReturn(100L);
        when(outboxMapper.selectBySessionForUpdate(10L)).thenReturn(task);
        when(summaryMapper.selectBySessionAndUserForUpdate(10L, 7L)).thenReturn(summary(10L, 7L, 4L, 50L));

        service.enqueue(10L, 7L, 40L);

        verify(outboxMapper, never()).deleteById(any(Long.class));
        verify(outboxMapper, never()).updateById(any(ConsultationContextSummaryOutbox.class));
        assertEquals(100L, task.getDesiredThroughMessageId());
    }

    @Test
    void normalEnqueueCannotReviveDeadTaskOrResetItsRetryBudget() {
        ContextSummaryOutboxService service = service();
        ConsultationContextSummaryOutbox task = task(30L, 10L, 7L, 80L, "DEAD", null);
        task.setAttemptCount(8);
        task.setLastErrorCode("UPSTREAM_TIMEOUT");
        when(sessionMapper.selectByIdForUpdate(10L)).thenReturn(session(10L, 7L));
        when(messageMapper.selectMaxMessageId(10L)).thenReturn(100L);
        when(outboxMapper.selectBySessionForUpdate(10L)).thenReturn(task);
        when(summaryMapper.selectBySessionAndUserForUpdate(10L, 7L)).thenReturn(null);

        service.enqueue(10L, 7L, 90L);

        assertEquals("DEAD", task.getStatus());
        assertEquals(8, task.getAttemptCount());
        assertEquals(90L, task.getDesiredThroughMessageId());
        assertEquals("UPSTREAM_TIMEOUT", task.getLastErrorCode());
        verify(outboxMapper).updateById(task);
    }

    @Test
    void growingPendingTargetPreservesAttemptsAndExistingBackoff() {
        ContextSummaryOutboxService service = service();
        ConsultationContextSummaryOutbox task = task(30L, 10L, 7L, 80L, "PENDING", null);
        task.setAttemptCount(5);
        task.setNextAttemptAt(LocalDateTime.now().plusMinutes(10));
        task.setLastErrorCode("UPSTREAM_TIMEOUT");
        LocalDateTime nextAttemptAt = task.getNextAttemptAt();
        when(sessionMapper.selectByIdForUpdate(10L)).thenReturn(session(10L, 7L));
        when(messageMapper.selectMaxMessageId(10L)).thenReturn(100L);
        when(outboxMapper.selectBySessionForUpdate(10L)).thenReturn(task);
        when(summaryMapper.selectBySessionAndUserForUpdate(10L, 7L)).thenReturn(null);

        service.enqueue(10L, 7L, 90L);

        assertEquals("PENDING", task.getStatus());
        assertEquals(90L, task.getDesiredThroughMessageId());
        assertEquals(5, task.getAttemptCount());
        assertEquals(nextAttemptAt, task.getNextAttemptAt());
        assertEquals("UPSTREAM_TIMEOUT", task.getLastErrorCode());
    }

    @Test
    void sessionDeleteRemovesSummaryAndOutboxAfterTakingTheSessionLock() {
        ContextSummaryOutboxService service = service();

        service.deleteForSession(10L);

        InOrder cleanup = inOrder(sessionMapper, outboxMapper, summaryMapper);
        cleanup.verify(sessionMapper).selectByIdForUpdate(10L);
        cleanup.verify(outboxMapper).deleteBySession(10L);
        cleanup.verify(summaryMapper).deleteById(10L);
    }

    @Test
    void processingClaimCapturesWatermarkAndSummaryVersionAsAnImmutableSnapshot() {
        ContextSummaryOutboxService service = service();
        ConsultationSession session = session(10L, 7L);
        ConsultationContextSummaryOutbox task = task(30L, 10L, 7L, 80L, "QUEUED", "dispatch");
        ConsultationContextSummary summary = summary(10L, 7L, 4L, 55L);
        when(outboxMapper.selectById(30L)).thenReturn(task);
        when(sessionMapper.selectByIdForUpdate(10L)).thenReturn(session);
        when(outboxMapper.selectByIdForUpdate(30L)).thenReturn(task);
        when(summaryMapper.selectBySessionAndUserForUpdate(10L, 7L)).thenReturn(summary);

        ContextSummaryOutboxService.ContextSummaryClaim claim = service.claimForProcessing(30L);
        task.setDesiredThroughMessageId(95L);

        assertEquals(80L, claim.targetThroughMessageId());
        assertEquals(4L, claim.expectedSummaryVersion());
        assertNotEquals("dispatch", claim.processingLeaseToken());
    }

    @Test
    void loadRequestUsesOnlyMessagesAfterTheSummaryWatermarkAndWithinBothBudgets() {
        ContextSummaryOutboxService service = service();
        ContextSummaryOutboxService.ContextSummaryClaim claim =
                new ContextSummaryOutboxService.ContextSummaryClaim(30L, 10L, 7L, 100L, 4L, "processing-lease");
        when(sessionMapper.selectByIdForUpdate(10L)).thenReturn(session(10L, 7L));
        when(outboxMapper.selectByIdForUpdate(30L))
                .thenReturn(task(30L, 10L, 7L, 100L, "PROCESSING", "processing-lease"));
        when(summaryMapper.selectBySessionAndUserForUpdate(10L, 7L))
                .thenReturn(summary(10L, 7L, 4L, 10L));
        when(messageMapper.selectWindowAfterWatermark(10L, 10L, 100L, 40))
                .thenReturn(List.of(
                        message(11L, 10L, "a".repeat(10000)),
                        message(12L, 10L, "b".repeat(10000)),
                        message(101L, 10L, "outside-watermark")
                ));

        ContextSummaryAgentRequest request = service.loadRequest(claim, 40, 16000);

        assertEquals(10L, request.sessionId());
        assertEquals(7L, request.userId());
        assertEquals(List.of(new ContextSummaryMessage(11L, 1, "a".repeat(10000))), request.messages());
        verify(messageMapper).selectWindowAfterWatermark(10L, 10L, 100L, 40);
    }

    @Test
    void firstSummaryRequestUsesNullInsteadOfAnInvalidEmptyPreviousSummary() {
        ContextSummaryOutboxService service = service();
        ContextSummaryOutboxService.ContextSummaryClaim claim =
                new ContextSummaryOutboxService.ContextSummaryClaim(30L, 10L, 7L, 100L, 0L, "processing-lease");
        when(sessionMapper.selectByIdForUpdate(10L)).thenReturn(session(10L, 7L));
        when(outboxMapper.selectByIdForUpdate(30L))
                .thenReturn(task(30L, 10L, 7L, 100L, "PROCESSING", "processing-lease"));
        when(summaryMapper.selectBySessionAndUserForUpdate(10L, 7L)).thenReturn(null);
        when(messageMapper.selectWindowAfterWatermark(10L, 0L, 100L, 40))
                .thenReturn(List.of(message(11L, 10L, "first summary message")));

        ContextSummaryAgentRequest request = service.loadRequest(claim, 40, 16_000);

        assertNull(request.previousSummary());
    }

    @Test
    void staleProcessingLeaseCannotCompleteOrOverwriteTheSummary() {
        ContextSummaryOutboxService service = service();
        when(sessionMapper.selectByIdForUpdate(10L)).thenReturn(session(10L, 7L));
        when(outboxMapper.selectByIdForUpdate(30L))
                .thenReturn(task(30L, 10L, 7L, 80L, "PROCESSING", "new-lease"));

        service.complete(
                claim("expired-lease"),
                new ContextSummaryAgentResponse(Map.of(), 80L, "context-summary-v1")
        );

        verify(summaryMapper, never()).updateSummaryCas(any(), any(), any(), any(), any(), any(), any());
        verify(summaryMapper, never()).insert(any(ConsultationContextSummary.class));
        verify(outboxMapper, never()).updateById(any(ConsultationContextSummaryOutbox.class));
        verify(outboxMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deadTaskRetryResetsBackoffAndStoresOperatorAuditFields() {
        ContextSummaryOutboxService service = service();
        ConsultationContextSummaryOutbox task = task(30L, 10L, 7L, 80L, "DEAD", null);
        task.setAttemptCount(8);
        task.setLastErrorCode("UPSTREAM_TIMEOUT");
        task.setManualRetryCount(2);
        when(sessionMapper.selectByIdForUpdate(10L)).thenReturn(session(10L, 7L));
        when(outboxMapper.selectBySessionForUpdate(10L)).thenReturn(task);
        when(outboxMapper.retryDeadForOwner(org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(99L), any(LocalDateTime.class)))
                .thenReturn(1);

        assertEquals(true, service.retryDead(10L, 99L));

        verify(outboxMapper).retryDeadForOwner(org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(99L), any(LocalDateTime.class));
        verify(outboxMapper, never()).updateById(any(ConsultationContextSummaryOutbox.class));
    }

    @Test
    void deadTaskRetryRejectsOutboxWhoseOwnerDoesNotMatchTheSession() {
        ContextSummaryOutboxService service = service();
        ConsultationContextSummaryOutbox task = task(30L, 10L, 8L, 80L, "DEAD", null);
        task.setAttemptCount(8);
        when(sessionMapper.selectByIdForUpdate(10L)).thenReturn(session(10L, 7L));
        when(outboxMapper.selectBySessionForUpdate(10L)).thenReturn(task);

        assertEquals(false, service.retryDead(10L, 99L));

        assertEquals("DEAD", task.getStatus());
        assertEquals(8, task.getAttemptCount());
        assertNull(task.getLastManualRetryBy());
        verify(outboxMapper, never()).retryDeadForOwner(any(Long.class), any(Long.class), any(Long.class), any(LocalDateTime.class));
        verify(outboxMapper, never()).updateById(any(ConsultationContextSummaryOutbox.class));
    }

    @Test
    void failurePersistsOnlyClassifiedErrorAndAppliesExponentialBackoff() {
        ContextSummaryOutboxService service = service();
        ConsultationContextSummaryOutbox task = task(30L, 10L, 7L, 80L, "PROCESSING", "processing-lease");
        task.setAttemptCount(3);
        when(sessionMapper.selectByIdForUpdate(10L)).thenReturn(session(10L, 7L));
        when(outboxMapper.selectByIdForUpdate(30L)).thenReturn(task);

        service.fail(claim("processing-lease"), "raw private message");

        assertEquals("PENDING", task.getStatus());
        assertEquals("SUMMARY_PROCESSING_FAILED", task.getLastErrorCode());
        assertEquals(3, task.getAttemptCount());
        org.junit.jupiter.api.Assertions.assertTrue(task.getNextAttemptAt().isAfter(LocalDateTime.now().plusSeconds(9)));
        assertNull(task.getLeaseToken());
        verify(metrics, times(1)).recordFailed();
        verify(outboxMapper).updateById(task);
    }

    @Test
    void completionAdvancesSummaryCasAndRequeuesWhenTargetGrewDuringProcessing() {
        ContextSummaryOutboxService service = service();
        ConsultationContextSummaryOutbox task = task(30L, 10L, 7L, 100L, "PROCESSING", "processing-lease");
        ConsultationContextSummary existingSummary = summary(10L, 7L, 4L, 55L);
        when(sessionMapper.selectByIdForUpdate(10L)).thenReturn(session(10L, 7L));
        when(outboxMapper.selectByIdForUpdate(30L)).thenReturn(task);
        when(summaryMapper.selectBySessionAndUserForUpdate(10L, 7L)).thenReturn(existingSummary);
        when(summaryMapper.updateSummaryCas(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        ContextSummaryOutboxService.ContextSummaryClaim claim = claim("processing-lease");

        service.complete(claim, new ContextSummaryAgentResponse(Map.of("facts", List.of("safe")), 80L,
                "context-summary-v1"));

        verify(summaryMapper).updateSummaryCas(org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(4L),
                any(String.class), org.mockito.ArgumentMatchers.eq(80L),
                org.mockito.ArgumentMatchers.eq("context-summary-v1"), any(LocalDateTime.class));
        assertEquals("PENDING", task.getStatus());
        assertEquals(0, task.getAttemptCount());
        assertNull(task.getLeaseToken());
        verify(outboxMapper, never()).deleteById(30L);
    }

    private ContextSummaryOutboxService service() {
        return new ContextSummaryOutboxService(
                outboxMapper,
                summaryMapper,
                messageMapper,
                sessionMapper,
                new ObjectMapper(),
                metrics
        );
    }

    private ContextSummaryOutboxService.ContextSummaryClaim claim(String lease) {
        return new ContextSummaryOutboxService.ContextSummaryClaim(30L, 10L, 7L, 80L, 4L, lease);
    }

    private ConsultationSession session(Long id, Long userId) {
        ConsultationSession session = new ConsultationSession();
        session.setId(id);
        session.setUserId(userId);
        return session;
    }

    private ConsultationContextSummary summary(Long sessionId, Long userId, Long version, Long watermark) {
        ConsultationContextSummary summary = new ConsultationContextSummary();
        summary.setSessionId(sessionId);
        summary.setUserId(userId);
        summary.setSummaryJson("{}");
        summary.setSummaryVersion(version);
        summary.setCoveredThroughMessageId(watermark);
        summary.setPromptVersion("context-summary-v1");
        return summary;
    }

    private ConsultationContextSummaryOutbox task(Long id, Long sessionId, Long userId,
                                                   Long target, String status, String lease) {
        ConsultationContextSummaryOutbox task = new ConsultationContextSummaryOutbox();
        task.setId(id);
        task.setSessionId(sessionId);
        task.setUserId(userId);
        task.setDesiredThroughMessageId(target);
        task.setStatus(status);
        task.setAttemptCount(0);
        task.setLeaseToken(lease);
        return task;
    }

    private ConsultationMessage message(Long id, Long sessionId, String content) {
        ConsultationMessage message = new ConsultationMessage();
        message.setId(id);
        message.setSessionId(sessionId);
        message.setSenderType(1);
        message.setContent(content);
        return message;
    }
}
