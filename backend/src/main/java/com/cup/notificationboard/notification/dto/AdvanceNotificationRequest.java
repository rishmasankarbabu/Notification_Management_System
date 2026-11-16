package com.cup.notificationboard.notification.dto;

import com.cup.notificationboard.notification.model.NotificationStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdvanceNotificationRequest(
        @NotNull NotificationStatus status,
        @Size(max = 2000) String resolutionNote
) {
}
