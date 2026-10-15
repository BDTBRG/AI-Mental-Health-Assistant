package com.amha.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("consultation_context_summary")
public class ConsultationContextSummary {
    @TableId
    private Long sessionId;
    private Long userId;
    private String summaryJson;
    private Long coveredThroughMessageId;
    private Long summaryVersion;
    private String promptVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
