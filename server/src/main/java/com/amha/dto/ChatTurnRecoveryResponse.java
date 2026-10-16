package com.amha.dto;

public record ChatTurnRecoveryResponse(
        String clientRequestId,
        String status,
        Long userMessageId,
        String userMessage,
        boolean recoverable
) {
}
