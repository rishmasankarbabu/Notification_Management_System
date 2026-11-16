package com.cup.notificationboard;

import com.cup.notificationboard.notification.persistence.NotificationRepository;
import com.cup.notificationboard.notification.dto.IncomingAlertDto;
import com.cup.notificationboard.notification.model.NotificationEntity;
import com.cup.notificationboard.notification.model.NotificationStatus;
import com.cup.notificationboard.notification.model.Severity;
import com.cup.notificationboard.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.security.username=test-operator",
        "app.security.password=test-only-password",
        "spring.jms.listener.auto-startup=false",
        "management.health.jms.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:notification_board_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
class NotificationApiIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationService notificationService;

    @BeforeEach
    void clearNotifications() {
        notificationRepository.deleteAll();
    }

    @Test
    void createsListsAndAdvancesNotification() throws Exception {
        String createdResponse = mockMvc.perform(post("/api/notifications")
                        .with(operator())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "High CPU usage",
                                  "description": "Alert from monitoring",
                                  "severity": "HIGH"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String id = com.jayway.jsonpath.JsonPath.read(createdResponse, "$.id");

        mockMvc.perform(get("/api/notifications")
                        .with(operator())
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id));

        mockMvc.perform(patch("/api/notifications/{id}/status", id)
                        .with(operator())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"IN_PROGRESS"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        mockMvc.perform(patch("/api/notifications/{id}/status", id)
                        .with(operator())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"RESOLVED","resolutionNote":"Restarted the worker"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolutionNote").value("Restarted the worker"));
    }

    @Test
    void rejectsInvalidInputAndIllegalTransitions() throws Exception {
        mockMvc.perform(post("/api/notifications")
                        .with(operator())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":" ","severity":"HIGH"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.invalidParams.title").exists());

        String id = notificationRepository.saveAndFlush(new NotificationEntity(
                UUID.randomUUID(),
                "Open alert",
                null,
                Severity.LOW,
                NotificationStatus.OPEN,
                Instant.now(),
                Instant.now(),
                null
        )).getId().toString();

        mockMvc.perform(patch("/api/notifications/{id}/status", id)
                        .with(operator())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"RESOLVED","resolutionNote":"Skipped work"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(patch("/api/notifications/{id}/status", id)
                        .with(operator())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"IN_PROGRESS"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/notifications/{id}/status", id)
                        .with(operator())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"RESOLVED"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deduplicatesARepeatedJmsMessageId() {
        IncomingAlertDto alert = new IncomingAlertDto("Queue alert", null, Severity.MEDIUM);

        var first = notificationService.create(alert, "ID:duplicate");
        var second = notificationService.create(alert, "ID:duplicate");

        org.junit.jupiter.api.Assertions.assertEquals(first.id(), second.id());
        assertTrue(notificationRepository.findBySourceMessageId("ID:duplicate").isPresent());
    }

    @Test
    void exposesOpenApiAndMinimalHealthCheck() throws Exception {
        mockMvc.perform(get("/v3/api-docs").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Notification Board API"))
                .andExpect(jsonPath("$.paths['/api/notifications'].post").exists());

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    private static RequestPostProcessor operator() {
        return httpBasic("test-operator", "test-only-password");
    }
}
