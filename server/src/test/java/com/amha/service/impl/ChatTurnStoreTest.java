package com.amha.service.impl;

import com.amha.common.BusinessException;
import com.amha.entity.ConsultationChatTurn;
import com.amha.entity.ConsultationMessage;
import com.amha.entity.ConsultationSession;
import com.amha.mapper.ConsultationChatTurnMapper;
import com.amha.mapper.ConsultationMessageMapper;
import com.amha.mapper.ConsultationSessionMapper;
import com.amha.mapper.ConsultationStreamEventMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatTurnStoreTest {
    private static final long SESSION_ID = 21L;
    private static final long USER_ID = 5L;

    @Mock
    private ConsultationChatTurnMapper turnMapper;
    @Mock
    private ConsultationStreamEventMapper eventMapper;
    @Mock
    private ConsultationMessageMapper messageMapper;
    @Mock
    private ConsultationSessionMapper sessionMapper;
    @Mock
    private ContextSummaryOutboxService summaryOutboxService;

    @Test
    void waitForContextCreatesOneRecoverableTurnMessageAndOutboxRequest() {
        ConsultationSession session = session();
        when(sessionMapper.selectByIdForUpdate(SESSION_ID)).thenReturn(session);
        when(turnMapper.selectBySessionAndRequestForUpdate(SESSION_ID, "request-1")).thenReturn(null);
        when(turnMapper.selectActiveBySessionForUpdate(SESSION_ID)).thenReturn(null);
        ChatTurnStore store = store();

        store.waitForContext(SESSION_ID, USER_ID, "request-1", "hash-1", "wait for summary", 18L);

        ArgumentCaptor<ConsultationChatTurn> turnCaptor = ArgumentCaptor.forClass(ConsultationChatTurn.class);
        ArgumentCaptor<ConsultationMessage> messageCaptor = ArgumentCaptor.forClass(ConsultationMessage.class);
        verify(turnMapper).insert(turnCaptor.capture());
        verify(messageMapper).insert(messageCaptor.capture());
        assertEquals("WAITING_CONTEXT", turnCaptor.getValue().getStatus());
        assertEquals("hash-1", turnCaptor.getValue().getRequestHash());
        assertEquals(1, messageCaptor.getValue().getSenderType());
        assertEquals("wait for summary", messageCaptor.getValue().getContent());
        assertEquals(1, session.getMessageCount());
        verify(summaryOutboxService).enqueue(SESSION_ID, USER_ID, 18L);
    }

    @Test
    void sameWaitingRequestMergesTheTargetWithoutWritingAnotherUserMessage() {
        ConsultationSession session = session();
        ConsultationChatTurn existing = turn("request-2", "hash-2", "WAITING_CONTEXT");
        when(sessionMapper.selectByIdForUpdate(SESSION_ID)).thenReturn(session);
        when(turnMapper.selectBySessionAndRequestForUpdate(SESSION_ID, "request-2")).thenReturn(existing);

        store().waitForContext(SESSION_ID, USER_ID, "request-2", "hash-2", "same message", 30L);
        store().waitForContext(SESSION_ID, USER_ID, "request-2", "hash-2", "same message", 35L);

        verify(messageMapper, never()).insert(any(ConsultationMessage.class));
        verify(turnMapper, never()).insert(any(ConsultationChatTurn.class));
        verify(summaryOutboxService).enqueue(SESSION_ID, USER_ID, 30L);
        verify(summaryOutboxService).enqueue(SESSION_ID, USER_ID, 35L);
    }

    @Test
    void requestHashConflictCannotChangeAWaitingTurn() {
        ConsultationSession session = session();
        ConsultationChatTurn existing = turn("request-3", "original-hash", "WAITING_CONTEXT");
        when(sessionMapper.selectByIdForUpdate(SESSION_ID)).thenReturn(session);
        when(turnMapper.selectBySessionAndRequestForUpdate(SESSION_ID, "request-3")).thenReturn(existing);

        BusinessException error = assertThrows(BusinessException.class,
                () -> store().waitForContext(SESSION_ID, USER_ID, "request-3", "different-hash",
                        "changed body", 40L));

        assertEquals("409", error.getCode());
        verify(messageMapper, never()).insert(any(ConsultationMessage.class));
        verify(summaryOutboxService, never()).enqueue(anyLong(), anyLong(), anyLong());
    }

    @Test
    void readyRetryMovesWaitingTurnToRunningWithoutDuplicatingItsMessage() {
        ConsultationSession session = session();
        ConsultationChatTurn existing = turn("request-4", "hash-4", "WAITING_CONTEXT");
        when(sessionMapper.selectByIdForUpdate(SESSION_ID)).thenReturn(session);
        when(turnMapper.selectBySessionAndRequestForUpdate(SESSION_ID, "request-4")).thenReturn(existing);

        ChatTurnStore.TurnStart result = store().beginTurn(SESSION_ID, USER_ID,
                "request-4", "hash-4", "already saved");

        assertTrue(result.shouldGenerate());
        assertEquals("RUNNING", existing.getStatus());
        assertEquals(1L, existing.getGenerationEpoch());
        verify(messageMapper, never()).insert(any(ConsultationMessage.class));
        verify(turnMapper).updateById(existing);
    }

    @Test
    void differentRequestCannotPassAnOldWaitingContextTurn() {
        ConsultationSession session = session();
        ConsultationChatTurn active = turn("older-request", "older-hash", "WAITING_CONTEXT");
        active.setUpdatedAt(LocalDateTime.now().minusHours(1));
        when(sessionMapper.selectByIdForUpdate(SESSION_ID)).thenReturn(session);
        when(turnMapper.selectBySessionAndRequestForUpdate(SESSION_ID, "new-request")).thenReturn(null);
        when(turnMapper.selectActiveBySessionForUpdate(SESSION_ID)).thenReturn(active);

        BusinessException error = assertThrows(BusinessException.class,
                () -> store().beginTurn(SESSION_ID, USER_ID, "new-request", "new-hash", "next"));

        assertEquals("409", error.getCode());
        assertEquals("WAITING_CONTEXT", active.getStatus());
        verify(messageMapper, never()).insert(any(ConsultationMessage.class));
    }

    @Test
    void rejectContextTurnPersistsOneTerminalTurnAndUserMessage() {
        ConsultationSession session = session();
        when(sessionMapper.selectByIdForUpdate(SESSION_ID)).thenReturn(session);
        when(turnMapper.selectBySessionAndRequestForUpdate(SESSION_ID, "too-large")).thenReturn(null);
        when(turnMapper.selectActiveBySessionForUpdate(SESSION_ID)).thenReturn(null);

        store().rejectContextTurn(SESSION_ID, USER_ID, "too-large", "hash-large", "oversized");

        ArgumentCaptor<ConsultationChatTurn> turnCaptor = ArgumentCaptor.forClass(ConsultationChatTurn.class);
        ArgumentCaptor<ConsultationMessage> messageCaptor = ArgumentCaptor.forClass(ConsultationMessage.class);
        verify(turnMapper).insert(turnCaptor.capture());
        verify(messageMapper).insert(messageCaptor.capture());
        assertEquals("CONTEXT_REJECTED", turnCaptor.getValue().getStatus());
        assertEquals("oversized", messageCaptor.getValue().getContent());
        assertEquals(1, session.getMessageCount());
        verify(summaryOutboxService, never()).enqueue(anyLong(), anyLong(), anyLong());
    }

    @Test
    void completedTurnEnqueuesTheMessageBeforeTheMostRecentTwelveForCompaction() {
        ConsultationSession session = session();
        ConsultationChatTurn turn = turn("complete-request", "complete-hash", "RUNNING");
        turn.setGenerationToken("token-1");
        turn.setGenerationEpoch(1L);
        when(sessionMapper.selectByIdForUpdate(SESSION_ID)).thenReturn(session);
        when(turnMapper.selectBySessionAndRequestForUpdate(SESSION_ID, "complete-request")).thenReturn(turn);
        when(messageMapper.selectSummaryTargetBeforeRecentMessages(SESSION_ID, USER_ID, 12)).thenReturn(42L);

        store().completeTurn(SESSION_ID, "complete-request", "token-1", "answer", 1, "done");

        verify(messageMapper).insert(any(ConsultationMessage.class));
        verify(summaryOutboxService).enqueue(SESSION_ID, USER_ID, 42L);
        assertEquals("COMPLETED", turn.getStatus());
        assertTrue(session.getMessageCount() > 0);
    }

    private ChatTurnStore store() {
        return new ChatTurnStore(turnMapper, eventMapper, messageMapper, sessionMapper, summaryOutboxService);
    }

    private static ConsultationSession session() {
        ConsultationSession session = new ConsultationSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setMessageCount(0);
        session.setStartedAt(LocalDateTime.now().minusMinutes(3));
        return session;
    }

    private static ConsultationChatTurn turn(String requestId, String hash, String status) {
        ConsultationChatTurn turn = new ConsultationChatTurn();
        turn.setSessionId(SESSION_ID);
        turn.setClientRequestId(requestId);
        turn.setRequestHash(hash);
        turn.setStatus(status);
        turn.setGenerationEpoch(0L);
        turn.setCreatedAt(LocalDateTime.now());
        turn.setUpdatedAt(LocalDateTime.now());
        return turn;
    }
}
