package com.cup.notificationboard;

import com.cup.notificationboard.notification.dto.IncomingAlertDto;
import com.cup.notificationboard.notification.messaging.AlertMessageListener;
import com.cup.notificationboard.notification.model.Severity;
import com.cup.notificationboard.notification.service.NotificationService;
import jakarta.jms.TextMessage;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AlertMessageListenerTests {

    private static jakarta.validation.ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void parsesAndForwardsValidTextMessageWithItsJmsId() throws Exception {
        NotificationService service = mock(NotificationService.class);
        AlertMessageListener listener = new AlertMessageListener(
                JsonMapper.builder().build(),
                validator,
                service
        );
        TextMessage message = mock(TextMessage.class);
        when(message.getJMSMessageID()).thenReturn("ID:alert-1");
        when(message.getText()).thenReturn("""
                {"title":"CPU alert","description":"High usage","severity":"HIGH"}
                """);

        listener.receive(message);

        ArgumentCaptor<IncomingAlertDto> alert = ArgumentCaptor.forClass(IncomingAlertDto.class);
        verify(service).create(alert.capture(), org.mockito.ArgumentMatchers.eq("ID:alert-1"));
        assertEquals("CPU alert", alert.getValue().title());
        assertEquals(Severity.HIGH, alert.getValue().severity());
    }

    @Test
    void rejectsInvalidPayloadAndMalformedJsonWithoutCreatingNotification() throws Exception {
        NotificationService service = mock(NotificationService.class);
        AlertMessageListener listener = new AlertMessageListener(
                JsonMapper.builder().build(),
                validator,
                service
        );
        TextMessage invalidMessage = mock(TextMessage.class);
        when(invalidMessage.getJMSMessageID()).thenReturn("ID:invalid");
        when(invalidMessage.getText()).thenReturn("""
                {"title":" ","severity":"HIGH"}
                """);

        assertThrows(ConstraintViolationException.class, () -> listener.receive(invalidMessage));

        TextMessage malformedMessage = mock(TextMessage.class);
        when(malformedMessage.getJMSMessageID()).thenReturn("ID:malformed");
        when(malformedMessage.getText()).thenReturn("{not-json");
        assertThrows(RuntimeException.class, () -> listener.receive(malformedMessage));

        verify(service, never()).create(
                org.mockito.ArgumentMatchers.any(IncomingAlertDto.class),
                org.mockito.ArgumentMatchers.anyString()
        );
    }
}
