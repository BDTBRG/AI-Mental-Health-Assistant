package com.amha.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
public class AgentMemoryVO {
    private Long id;
    private String content;
    private String sourceType;
    private LocalDate expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
