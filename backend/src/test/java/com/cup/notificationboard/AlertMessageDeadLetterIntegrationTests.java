package com.cup.notificationboard;

import com.cup.notificationboard.notification.persistence.NotificationRepository;
import com.cup.notificationboard.notification.messaging.AlertMessageListener;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.artemis.autoconfigure.ArtemisConfigurationCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "app.security.username=test-operator",
        "app.security.password=test-only-password",
        "management.health.jms.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:dead_letter_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.artemis.mode=embedded",
        "spring.artemis.embedded.enabled=true",
        "spring.artemis.embedded.persistent=false",
        "spring.artemis.embedded.queues=alert.notifications,DLQ"
})
@Import(AlertMessageDeadLetterIntegrationTests.BrokerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AlertMessageDeadLetterIntegrationTests {

    @Autowired
    private JmsTemplate jmsTemplate;

    @Autowired
    private NotificationRepository notificationRepository;

    @MockitoSpyBean
    private AlertMessageListener alertMessageListener;

    @Test
    void movesRepeatedlyRejectedAlertToDeadLetterQueueAfterThreeDeliveries() throws Exception {
        String invalidAlert = """
                {"title":" ","description":"Invalid alert","severity":"HIGH"}
                """;
        jmsTemplate.send("alert.notifications", session -> session.createTextMessage(invalidAlert));

        jmsTemplate.setReceiveTimeout(5_000);
        TextMessage deadLetter = (TextMessage) jmsTemplate.receive("DLQ");

        assertNotNull(deadLetter, "The rejected alert should be routed to DLQ");
        assertEquals(invalidAlert, deadLetter.getText());
        assertNotNull(deadLetter.getJMSMessageID());
        verify(alertMessageListener, timeout(5_000).times(3)).receive(any());
        assertEquals(0, notificationRepository.count());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BrokerConfiguration {

        @Bean
        ArtemisConfigurationCustomizer deadLetterSettings() {
            return configuration -> configuration.addAddressSetting(
                    "#",
                    new AddressSettings()
                            .setDeadLetterAddress(SimpleString.of("DLQ"))
                            .setMaxDeliveryAttempts(3)
                            .setRedeliveryDelay(10)
            );
        }
    }
}
