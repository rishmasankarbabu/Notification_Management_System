package com.cup.notificationboard.notification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications", indexes = {
        @Index(name = "idx_notifications_created_at", columnList = "created_at")
})
public class NotificationEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(length = 2000)
    private String resolutionNote;

    private Instant resolvedAt;

    @Column(name = "source_message_id", unique = true, length = 255)
    private String sourceMessageId;

    @Version
    private long version;

    protected NotificationEntity() {
    }

    public NotificationEntity(
            UUID id,
            String title,
            String description,
            Severity severity,
            NotificationStatus status,
            Instant createdAt,
            Instant updatedAt,
            String sourceMessageId
    ) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.severity = severity;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.sourceMessageId = sourceMessageId;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Severity getSeverity() {
        return severity;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void advanceTo(NotificationStatus nextStatus, String note, Instant now) {
        this.status = nextStatus;
        this.updatedAt = now;
        if (nextStatus == NotificationStatus.RESOLVED) {
            this.resolutionNote = note;
            this.resolvedAt = now;
        }
    }
}
