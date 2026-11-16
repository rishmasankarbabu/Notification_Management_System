package com.cup.notificationboard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.security.username=test-operator",
        "app.security.password=test-only-password",
        "spring.jms.listener.auto-startup=false",
        "management.health.jms.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:rate_limit_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
class ApiRateLimitFilterTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsTheFirstRequestAfterThePerClientLimit() throws Exception {
        for (int request = 0; request < 120; request++) {
            mockMvc.perform(get("/api/notifications")
                            .with(httpBasic("test-operator", "test-only-password"))
                            .with(requestBuilder -> {
                                requestBuilder.setRemoteAddr("192.0.2.120");
                                return requestBuilder;
                            }))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/notifications")
                        .with(httpBasic("test-operator", "test-only-password"))
                        .with(requestBuilder -> {
                            requestBuilder.setRemoteAddr("192.0.2.120");
                            return requestBuilder;
                        }))
                .andExpect(status().is(429))
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Too Many Requests")));
    }
}
