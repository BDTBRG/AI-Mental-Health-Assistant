package com.amha.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("consultation_context_summary_outbox")
public class ConsultationContextSummaryOutbox {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long userId;
    private Long desiredThroughMessageId;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptAt;
    private String leaseToken;
    private LocalDateTime leaseUntil;
    private String lastErrorCode;
    private Integer manualRetryCount;
    private Long lastManualRetryBy;
    private LocalDateTime lastManualRetryAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
