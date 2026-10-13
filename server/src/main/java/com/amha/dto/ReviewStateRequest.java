package com.amha.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReviewStateRequest {
    @NotBlank
    private String targetStatus;
    @NotNull
    private Integer expectedVersion;
}
