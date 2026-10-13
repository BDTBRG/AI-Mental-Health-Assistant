package com.amha.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("emotion_diary_access_audit")
public class EmotionDiaryAccessAudit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long diaryId;
    private Long actorUserId;
    private String action;
    private LocalDateTime createdAt;
}
