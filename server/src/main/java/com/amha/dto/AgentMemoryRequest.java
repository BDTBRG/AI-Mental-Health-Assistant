package com.amha.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class AgentMemoryRequest {
    @NotBlank
    @Size(max = 500)
    private String content;
    private LocalDate expiresAt;
}
