package com.cup.notificationboard.notification.api;

import com.cup.notificationboard.notification.dto.AdvanceNotificationRequest;
import com.cup.notificationboard.notification.dto.CreateNotificationRequest;
import com.cup.notificationboard.notification.dto.NotificationPageResponse;
import com.cup.notificationboard.notification.dto.NotificationResponse;
import com.cup.notificationboard.notification.model.NotificationStatus;
import com.cup.notificationboard.notification.model.Severity;
import com.cup.notificationboard.notification.realtime.NotificationSseHub;
import com.cup.notificationboard.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications")
@SecurityRequirement(name = "basicAuth")
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationSseHub sseHub;

    public NotificationController(NotificationService notificationService, NotificationSseHub sseHub) {
        this.notificationService = notificationService;
        this.sseHub = sseHub;
    }

    @GetMapping
    @Operation(summary = "List notifications, newest first")
    public NotificationPageResponse list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) Severity severity
    ) {
        PageRequest pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))
        );
        return notificationService.list(pageable, status, severity);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a notification by ID")
    public NotificationResponse get(@PathVariable UUID id) {
        return notificationService.get(id);
    }

    @PostMapping
    @Operation(summary = "Create an OPEN notification")
    public ResponseEntity<NotificationResponse> create(
            @Valid @RequestBody CreateNotificationRequest request
    ) {
        NotificationResponse created = notificationService.create(request);
        return ResponseEntity.created(URI.create("/api/notifications/" + created.id())).body(created);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Advance a notification to its next lifecycle status")
    public NotificationResponse advance(
            @PathVariable UUID id,
            @Valid @RequestBody AdvanceNotificationRequest request
    ) {
        return notificationService.advance(id, request);
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Subscribe to notification create and update events")
    public ResponseEntity<SseEmitter> events() {
        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .body(sseHub.subscribe());
    }
}
