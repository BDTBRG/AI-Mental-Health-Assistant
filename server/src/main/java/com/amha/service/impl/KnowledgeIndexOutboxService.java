package com.amha.service.impl;

import com.amha.entity.KnowledgeIndexOutbox;
import com.amha.mapper.KnowledgeIndexOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KnowledgeIndexOutboxService {
    private final KnowledgeIndexOutboxMapper outboxMapper;

    @Transactional
    public void enqueueArticle(Long articleId) {
        KnowledgeIndexOutbox task = outboxMapper.selectByArticleIdForUpdate(articleId);
        LocalDateTime now = LocalDateTime.now();
        if (task == null) {
            task = new KnowledgeIndexOutbox();
            task.setArticleId(articleId);
            task.setDesiredVersion(1L);
            task.setStatus("PENDING");
            task.setAttempts(0);
            task.setNextAttemptAt(now);
            outboxMapper.insert(task);
            return;
        }

        task.setDesiredVersion((task.getDesiredVersion() == null ? 0L : task.getDesiredVersion()) + 1L);
        task.setLastError(null);
        task.setUpdatedAt(now);
        if (!"PROCESSING".equals(task.getStatus())) {
            task.setStatus("PENDING");
            task.setAttempts(0);
            task.setNextAttemptAt(now);
            task.setProcessingVersion(null);
            task.setLeaseToken(null);
            task.setLeaseUntil(null);
        }
        outboxMapper.updateById(task);
    }

    @Transactional
    public KnowledgeIndexOutbox claimNextForDispatch() {
        KnowledgeIndexOutbox task = outboxMapper.selectNextReadyForUpdate();
        if (task == null) return null;
        task.setStatus("QUEUED");
        task.setLeaseToken(UUID.randomUUID().toString());
        task.setLeaseUntil(LocalDateTime.now().plusSeconds(30));
        task.setUpdatedAt(LocalDateTime.now());
        outboxMapper.updateById(task);
        return task;
    }

    @Transactional
    public KnowledgeIndexOutbox claimForProcessing(Long taskId, String dispatchToken) {
        KnowledgeIndexOutbox task = outboxMapper.selectByIdForUpdate(taskId);
        if (task == null || !"QUEUED".equals(task.getStatus())
                || !java.util.Objects.equals(dispatchToken, task.getLeaseToken())) return null;
        task.setStatus("PROCESSING");
        task.setProcessingVersion(task.getDesiredVersion());
        task.setAttempts((task.getAttempts() == null ? 0 : task.getAttempts()) + 1);
        task.setLeaseToken(UUID.randomUUID().toString());
        task.setLeaseUntil(LocalDateTime.now().plusMinutes(2));
        task.setUpdatedAt(LocalDateTime.now());
        outboxMapper.updateById(task);
        return task;
    }

    @Transactional
    public boolean returnToPending(Long taskId, String dispatchToken) {
        LocalDateTime now = LocalDateTime.now();
        return outboxMapper.update(null, new LambdaUpdateWrapper<KnowledgeIndexOutbox>()
                .eq(KnowledgeIndexOutbox::getId, taskId)
                .eq(KnowledgeIndexOutbox::getStatus, "QUEUED")
                .eq(KnowledgeIndexOutbox::getLeaseToken, dispatchToken)
                .set(KnowledgeIndexOutbox::getStatus, "PENDING")
                .set(KnowledgeIndexOutbox::getNextAttemptAt, now.plusSeconds(5))
                .set(KnowledgeIndexOutbox::getLeaseToken, null)
                .set(KnowledgeIndexOutbox::getLeaseUntil, null)
                .set(KnowledgeIndexOutbox::getLastError, "REDIS_PUBLISH_FAILED")
                .set(KnowledgeIndexOutbox::getUpdatedAt, now)) > 0;
    }

    @Transactional
    public boolean complete(KnowledgeIndexOutbox claimedTask) {
        KnowledgeIndexOutbox task = outboxMapper.selectByIdForUpdate(claimedTask.getId());
        if (!isCurrentClaim(task, claimedTask)) return false;
        LocalDateTime now = LocalDateTime.now();
        boolean changedWhileProcessing = !java.util.Objects.equals(
                task.getDesiredVersion(), task.getProcessingVersion());
        task.setStatus(changedWhileProcessing ? "PENDING" : "DONE");
        task.setAttempts(changedWhileProcessing ? 0 : task.getAttempts());
        task.setNextAttemptAt(changedWhileProcessing ? now : task.getNextAttemptAt());
        clearLease(task);
        task.setLastError(null);
        task.setUpdatedAt(now);
        outboxMapper.updateById(task);
        return true;
    }

    @Transactional
    public boolean fail(KnowledgeIndexOutbox claimedTask, String errorCode) {
        KnowledgeIndexOutbox task = outboxMapper.selectByIdForUpdate(claimedTask.getId());
        if (!isCurrentClaim(task, claimedTask)) return false;
        LocalDateTime now = LocalDateTime.now();
        boolean changedWhileProcessing = !java.util.Objects.equals(
                task.getDesiredVersion(), task.getProcessingVersion());
        int attempts = task.getAttempts() == null ? 1 : task.getAttempts();
        long delaySeconds = Math.min(3600, 5L << Math.min(10, Math.max(0, attempts - 1)));
        // Index deletion/unpublish must eventually converge; keep retrying with bounded backoff.
        task.setStatus("PENDING");
        task.setAttempts(changedWhileProcessing ? 0 : attempts);
        task.setNextAttemptAt(changedWhileProcessing ? now : now.plusSeconds(delaySeconds));
        clearLease(task);
        task.setLastError(errorCode);
        task.setUpdatedAt(now);
        outboxMapper.updateById(task);
        return true;
    }

    private boolean isCurrentClaim(KnowledgeIndexOutbox current, KnowledgeIndexOutbox claimed) {
        return current != null && "PROCESSING".equals(current.getStatus())
                && java.util.Objects.equals(current.getLeaseToken(), claimed.getLeaseToken());
    }

    private void clearLease(KnowledgeIndexOutbox task) {
        task.setLeaseToken(null);
        task.setLeaseUntil(null);
        task.setProcessingVersion(null);
    }
}
