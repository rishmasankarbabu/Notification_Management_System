package com.cup.notificationboard.notification.dto;

import com.cup.notificationboard.notification.model.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record IncomingAlertDto(
        @NotBlank @Size(max = 100) String title,
        String description,
        @NotNull Severity severity
) {
}
