package com.cup.notificationboard.notification.api;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@SecurityRequirement(name = "basicAuth")
public class ApiSupportController {

    @GetMapping("/api/csrf")
    public Map<String, String> csrfToken(CsrfToken csrfToken) {
        return Map.of("token", csrfToken.getToken());
    }
}
