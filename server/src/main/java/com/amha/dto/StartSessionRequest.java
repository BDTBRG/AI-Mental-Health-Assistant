package com.amha.dto;

import lombok.Data;
import jakarta.validation.constraints.Size;

@Data
public class StartSessionRequest {
    @Size(max = 500)
    private String initialMessage;

    @Size(max = 200)
    private String sessionTitle;

    private Boolean reviewConsent;

    @Size(max = 64)
    private String clientRequestId;
}
