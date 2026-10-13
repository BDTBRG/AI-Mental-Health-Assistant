package com.amha.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_index_outbox")
public class KnowledgeIndexOutbox {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long articleId;
    private Long desiredVersion;
    private Long processingVersion;
    private String status;
    private Integer attempts;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime leaseUntil;
    private String leaseToken;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
