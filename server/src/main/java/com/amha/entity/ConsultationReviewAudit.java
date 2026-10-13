package com.amha.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("consultation_review_audit")
public class ConsultationReviewAudit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long actorUserId;
    private String action;
    private String fromStatus;
    private String toStatus;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
