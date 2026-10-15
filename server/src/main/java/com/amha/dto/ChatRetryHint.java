package com.amha.dto;

public record ChatRetryHint(String clientRequestId, boolean retryable, long retryAfterMs) {
}
