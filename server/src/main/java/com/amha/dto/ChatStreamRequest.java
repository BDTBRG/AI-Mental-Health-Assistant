package com.amha.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChatStreamRequest {
    private String sessionId;
    @Size(max = 64)
    private String clientRequestId;
    @Size(max = 8000)
    private String userMessage;
}
