package com.amha.service.impl;

import com.amha.agent.RealChatAgent;
import com.amha.entity.AgentCheckpointPurgeOutbox;
import com.amha.entity.ConsultationSession;
import com.amha.mapper.ConsultationSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCheckpointPurgeOutboxWorkerTest {
    private AgentCheckpointPurgeOutboxService outboxService;
    private RealChatAgent realChatAgent;
    private ConsultationSessionMapper sessionMapper;
    private AgentCheckpointPurgeOutboxWorker worker;

    @BeforeEach
    void setUp() {
        outboxService = mock(AgentCheckpointPurgeOutboxService.class);
        realChatAgent = mock(RealChatAgent.class);
        sessionMapper = mock(ConsultationSessionMapper.class);
        worker = new AgentCheckpointPurgeOutboxWorker(
                outboxService, realChatAgent, sessionMapper);
    }

    @Test
    void existingSessionUsesTemporaryCheckpointPurge() {
        AgentCheckpointPurgeOutbox task = task(17L);
        when(outboxService.claimNextForProcessing()).thenReturn(task, null);
        when(sessionMapper.selectById(17L)).thenReturn(new ConsultationSession());

        worker.processReadyTasks();

        verify(realChatAgent).purgeSessionState(17L, false);
        verify(outboxService).complete(task);
        verify(outboxService, never()).fail(task, "AGENT_PURGE_FAILED");
    }

    @Test
    void missingSessionUsesPermanentTombstoneAndPurge() {
        AgentCheckpointPurgeOutbox task = task(18L);
        when(outboxService.claimNextForProcessing()).thenReturn(task, null);
        when(sessionMapper.selectById(18L)).thenReturn(null);

        worker.processReadyTasks();

        verify(realChatAgent).purgeSessionState(18L, true);
        verify(outboxService).complete(task);
    }

    private AgentCheckpointPurgeOutbox task(Long sessionId) {
        AgentCheckpointPurgeOutbox task = new AgentCheckpointPurgeOutbox();
        task.setId(sessionId);
        task.setSessionId(sessionId);
        task.setAttempts(1);
        return task;
    }
}
