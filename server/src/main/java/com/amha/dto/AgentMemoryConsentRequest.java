package com.amha.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AgentMemoryConsentRequest {
    @NotNull
    private Boolean allowed;
}
