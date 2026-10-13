package com.amha.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class AgentMemoryCheckpointPurger {
    private final AgentCheckpointPurgeOutboxService outboxService;

    @TransactionalEventListener(phase = org.springframework.transaction.event.TransactionPhase.BEFORE_COMMIT)
    public void enqueuePurgeBeforeMemoryChangeCommits(AgentMemoryChangedEvent event) {
        outboxService.enqueueUserSessions(event.userId());
    }
}
