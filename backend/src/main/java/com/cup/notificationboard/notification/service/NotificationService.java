package com.cup.notificationboard.notification.service;

import com.cup.notificationboard.notification.dto.AdvanceNotificationRequest;
import com.cup.notificationboard.notification.dto.CreateNotificationRequest;
import com.cup.notificationboard.notification.dto.IncomingAlertDto;
import com.cup.notificationboard.notification.dto.NotificationPageResponse;
import com.cup.notificationboard.notification.dto.NotificationResponse;
import com.cup.notificationboard.notification.model.NotificationEntity;
import com.cup.notificationboard.notification.model.NotificationStatus;
import com.cup.notificationboard.notification.model.Severity;
import com.cup.notificationboard.notification.persistence.NotificationRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class NotificationService {

    private final NotificationRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    public NotificationService(
            NotificationRepository repository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public NotificationResponse create(CreateNotificationRequest request) {
        return create(request.title(), request.description(), request.severity(), null);
    }

    @Transactional
    public NotificationResponse create(IncomingAlertDto alert, String sourceMessageId) {
        return create(alert.title(), alert.description(), alert.severity(), sourceMessageId);
    }

    private NotificationResponse create(
            String title,
            String description,
            Severity severity,
            String sourceMessageId
    ) {
        validate(title, severity);

        if (sourceMessageId != null) {
            var existing = repository.findBySourceMessageId(sourceMessageId);
            if (existing.isPresent()) {
                return toResponse(existing.get());
            }
        }

        Instant now = Instant.now();
        NotificationEntity entity = new NotificationEntity(
                UUID.randomUUID(),
                title,
                description,
                severity,
                NotificationStatus.OPEN,
                now,
                now,
                sourceMessageId
        );
        NotificationResponse response = toResponse(repository.saveAndFlush(entity));
        eventPublisher.publishEvent(new NotificationChanged("notification-created", response));
        return response;
    }

    @Transactional(readOnly = true)
    public NotificationPageResponse list(
            Pageable pageable,
            NotificationStatus status,
            Severity severity
    ) {
        List<Specification<NotificationEntity>> filters = new ArrayList<>();
        if (status != null) {
            filters.add((root, query, criteriaBuilder) ->
                    criteriaBuilder.equal(root.get("status"), status));
        }
        if (severity != null) {
            filters.add((root, query, criteriaBuilder) ->
                    criteriaBuilder.equal(root.get("severity"), severity));
        }

        Specification<NotificationEntity> specification = Specification.allOf(filters);
        Page<NotificationResponse> page = repository.findAll(specification, pageable)
                .map(NotificationService::toResponse);
        return new NotificationPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }

    @Transactional(readOnly = true)
    public NotificationResponse get(UUID id) {
        return repository.findById(id)
                .map(NotificationService::toResponse)
                .orElseThrow(() -> new NotificationNotFoundException(
                        "Notification " + id + " was not found"
                ));
    }

    @Transactional
    public NotificationResponse advance(UUID id, AdvanceNotificationRequest request) {
        NotificationEntity entity = repository.findById(id)
                .orElseThrow(() -> new NotificationNotFoundException(
                        "Notification " + id + " was not found"
                ));

        NotificationStatus expected = switch (entity.getStatus()) {
            case OPEN -> NotificationStatus.IN_PROGRESS;
            case IN_PROGRESS -> NotificationStatus.RESOLVED;
            case RESOLVED -> null;
        };
        if (request.status() == null || request.status() != expected) {
            throw new InvalidStatusTransitionException(
                    "Only the next status in the notification lifecycle is allowed"
            );
        }
        if (request.status() == NotificationStatus.RESOLVED
                && (request.resolutionNote() == null || request.resolutionNote().isBlank())) {
            throw new InvalidNotificationException(
                    "A non-blank resolution note is required to resolve a notification"
            );
        }

        entity.advanceTo(request.status(), request.resolutionNote(), Instant.now());
        NotificationResponse response = toResponse(repository.saveAndFlush(entity));
        eventPublisher.publishEvent(new NotificationChanged("notification-updated", response));
        return response;
    }

    private static void validate(String title, Severity severity) {
        if (title == null || title.isBlank()) {
            throw new InvalidNotificationException("Title must not be blank");
        }
        if (title.length() > 100) {
            throw new InvalidNotificationException("Title must be at most 100 characters");
        }
        if (severity == null) {
            throw new InvalidNotificationException("Severity is required");
        }
    }

    private static NotificationResponse toResponse(NotificationEntity entity) {
        return new NotificationResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getSeverity(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getResolutionNote(),
                entity.getResolvedAt()
        );
    }
}
