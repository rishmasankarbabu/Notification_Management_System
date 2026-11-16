package com.cup.notificationboard.notification.messaging;

import com.cup.notificationboard.notification.dto.IncomingAlertDto;
import com.cup.notificationboard.notification.service.NotificationService;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class AlertMessageListener {

    private static final Logger log = LoggerFactory.getLogger(AlertMessageListener.class);

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final NotificationService notificationService;

    public AlertMessageListener(
            ObjectMapper objectMapper,
            Validator validator,
            NotificationService notificationService
    ) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.notificationService = notificationService;
    }

    @JmsListener(destination = "${app.messaging.queue}")
    public void receive(Message message) throws JMSException {
        if (!(message instanceof TextMessage textMessage)) {
            throw new IllegalArgumentException("Expected a JSON TextMessage");
        }

        String messageId = message.getJMSMessageID();
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("JMS message ID is required for safe redelivery");
        }

        IncomingAlertDto alert;
        try {
            alert = objectMapper.readValue(textMessage.getText(), IncomingAlertDto.class);
        } catch (RuntimeException exception) {
            log.warn("Rejected malformed alert message; JMS message ID={}", messageId);
            throw exception;
        }
        if (alert == null) {
            throw new IllegalArgumentException("Alert payload must be a JSON object");
        }

        var violations = validator.validate(alert);
        if (!violations.isEmpty()) {
            log.warn("Rejected invalid alert message; JMS message ID={}, violations={}",
                    messageId,
                    violations.size());
            throw new ConstraintViolationException(violations);
        }

        notificationService.create(alert, messageId);
    }
}
