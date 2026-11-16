package com.cup.notificationboard;

import com.cup.notificationboard.notification.dto.AdvanceNotificationRequest;
import com.cup.notificationboard.notification.dto.IncomingAlertDto;
import com.cup.notificationboard.notification.model.NotificationStatus;
import com.cup.notificationboard.notification.model.Severity;
import com.cup.notificationboard.notification.realtime.NotificationSseHub;
import com.cup.notificationboard.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.security.username=test-operator",
                "app.security.password=test-only-password",
                "spring.jms.listener.auto-startup=false",
                "management.health.jms.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:sse_test;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.flyway.enabled=false",
                "spring.artemis.mode=embedded",
                "spring.artemis.embedded.enabled=true",
                "spring.artemis.embedded.persistent=false",
                "spring.artemis.embedded.queues=alert.notifications",
                "server.shutdown=immediate"
        }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationSseIntegrationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private NotificationService notificationService;

    @MockitoSpyBean
    private NotificationSseHub notificationSseHub;

    @Test
    void publishesCreatedNotificationToAnAuthenticatedSseClient() throws Exception {
        String credentials = Base64.getEncoder().encodeToString(
                "test-operator:test-only-password".getBytes(StandardCharsets.UTF_8)
        );
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/notifications/events"))
                .header("Authorization", "Basic " + credentials)
                .GET()
                .build();

        CompletableFuture<HttpResponse<InputStream>> responseFuture = HttpClient.newHttpClient()
                .sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> response = null;
        try {
            verify(notificationSseHub, timeout(5_000)).subscribe();
            var created = notificationService.create(new IncomingAlertDto(
                    "SSE integration alert",
                    "Created by the SSE test",
                    Severity.HIGH
            ), "ID:sse-test");

            response = responseFuture.get(5, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode());
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    response.body(),
                    StandardCharsets.UTF_8
            ));
            String createdEvent = readEventContaining(reader, "SSE integration alert");
            assertTrue(createdEvent.contains("event:notification-created"));

            notificationService.advance(
                    created.id(),
                    new AdvanceNotificationRequest(NotificationStatus.IN_PROGRESS, null)
            );
            String updatedEvent = readEventContaining(reader, "\"status\":\"IN_PROGRESS\"");
            assertTrue(updatedEvent.contains("event:notification-updated"));
        } finally {
            if (response != null) {
                response.body().close();
            } else {
                responseFuture.cancel(true);
            }
        }
    }

    private static String readEventContaining(BufferedReader reader, String expectedData) throws IOException {
        StringBuilder event = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (event.toString().contains(expectedData)) {
                    return event.toString();
                }
                event.setLength(0);
            } else {
                event.append(line).append('\n');
            }
        }
        return event.toString();
    }
}
