package com.amha.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReviewConsentRequest {
    @NotNull
    private Boolean allowed;
}
