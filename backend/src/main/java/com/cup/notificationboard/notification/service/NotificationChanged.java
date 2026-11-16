package com.cup.notificationboard.notification.service;

import com.cup.notificationboard.notification.dto.NotificationResponse;

public record NotificationChanged(String eventType, NotificationResponse notification) {
}
