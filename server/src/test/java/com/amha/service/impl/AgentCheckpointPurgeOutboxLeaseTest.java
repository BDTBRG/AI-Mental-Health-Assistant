package com.amha.service.impl;

import com.amha.entity.AgentCheckpointPurgeOutbox;
import com.amha.mapper.AgentCheckpointPurgeOutboxMapper;
import com.amha.mapper.ConsultationSessionMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentCheckpointPurgeOutboxLeaseTest {

    @Test
    void leaseExceedsMaximumAgentPurgeRequestTimeout() {
        AgentCheckpointPurgeOutboxMapper outboxMapper = mock(AgentCheckpointPurgeOutboxMapper.class);
        ConsultationSessionMapper sessionMapper = mock(ConsultationSessionMapper.class);
        AgentCheckpointPurgeOutboxService service = new AgentCheckpointPurgeOutboxService(
                outboxMapper, sessionMapper);
        AgentCheckpointPurgeOutbox task = new AgentCheckpointPurgeOutbox();
        when(outboxMapper.selectNextReadyForUpdate()).thenReturn(task);
        LocalDateTime beforeClaim = LocalDateTime.now();

        service.claimNextForProcessing();

        Duration lease = Duration.between(beforeClaim, task.getLeaseUntil());
        assertTrue(lease.compareTo(Duration.ofMinutes(10)) > 0,
                "the lease must outlast RealChatAgent's 10-minute purge timeout");
        assertTrue(lease.compareTo(Duration.ofMinutes(12).plusSeconds(1)) <= 0,
                "the lease should stay within the configured 12-minute recovery window");
    }
}
