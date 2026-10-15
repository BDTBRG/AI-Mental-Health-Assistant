package com.amha.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("consultation_chat_turn")
public class ConsultationChatTurn {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    @TableField("client_request_id")
    private String clientRequestId;
    private String requestHash;
    private String status;
    private String generationToken;
    private Long generationEpoch;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
