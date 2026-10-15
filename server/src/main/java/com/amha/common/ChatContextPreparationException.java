package com.amha.common;

import lombok.Getter;

@Getter
public class ChatContextPreparationException extends RuntimeException {
    private final String errorCode;
    private final String clientRequestId;
    private final boolean retryable;
    private final long retryAfterMs;

    public ChatContextPreparationException(String errorCode, String message, String clientRequestId,
                                           boolean retryable, long retryAfterMs) {
        super(message);
        this.errorCode = errorCode;
        this.clientRequestId = clientRequestId;
        this.retryable = retryable;
        this.retryAfterMs = retryable ? Math.max(1L, retryAfterMs) : 0L;
    }
}
