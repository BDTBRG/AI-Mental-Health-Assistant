package com.amha.service.impl;

import com.amha.agent.RealChatAgent;
import com.amha.entity.AgentCheckpointPurgeOutbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class AgentCheckpointPurgeOutboxWorker {
    private final AgentCheckpointPurgeOutboxService outboxService;
    private final RealChatAgent realChatAgent;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:2000}")
    public void processReadyTasks() {
        for (int processed = 0; processed < 10; processed++) {
            AgentCheckpointPurgeOutbox task = outboxService.claimNextForProcessing();
            if (task == null) return;
            try {
                realChatAgent.purgeSessionState(task.getSessionId());
                outboxService.complete(task);
            } catch (Exception error) {
                outboxService.fail(task, "AGENT_PURGE_FAILED");
                log.warn("Agent checkpoint purge task {} failed on attempt {}",
                        task.getId(), task.getAttempts());
            }
        }
    }
}
