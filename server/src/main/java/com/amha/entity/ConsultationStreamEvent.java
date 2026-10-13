package com.amha.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("consultation_stream_event")
public class ConsultationStreamEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    @TableField("client_request_id")
    private String clientRequestId;
    private Integer sequence;
    private String eventType;
    private String payload;
    private LocalDateTime createdAt;
}
