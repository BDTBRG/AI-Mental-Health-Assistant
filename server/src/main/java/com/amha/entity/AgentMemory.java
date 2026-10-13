package com.amha.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("agent_memory")
public class AgentMemory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String content;
    private String sourceType;
    private LocalDate expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
