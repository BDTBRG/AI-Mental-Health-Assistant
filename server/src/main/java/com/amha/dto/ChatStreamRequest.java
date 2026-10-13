package com.amha.dto;

import lombok.Data;

@Data
public class ChatStreamRequest {
    private String sessionId;
    private String clientRequestId;
    private String userMessage;
}
