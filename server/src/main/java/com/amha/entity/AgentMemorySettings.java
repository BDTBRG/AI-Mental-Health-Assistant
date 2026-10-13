package com.amha.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_memory_settings")
public class AgentMemorySettings {
    @TableId(type = IdType.INPUT)
    private Long userId;
    private Boolean personalizationEnabled;
    private LocalDateTime updatedAt;
}
