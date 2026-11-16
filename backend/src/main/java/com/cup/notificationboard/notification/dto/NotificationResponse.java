package com.cup.notificationboard.notification.dto;

import com.cup.notificationboard.notification.model.NotificationStatus;
import com.cup.notificationboard.notification.model.Severity;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        String title,
        String description,
        Severity severity,
        NotificationStatus status,
        Instant createdAt,
        Instant updatedAt,
        String resolutionNote,
        Instant resolvedAt
) {
}
