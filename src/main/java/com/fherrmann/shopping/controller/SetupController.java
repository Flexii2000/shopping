package com.fherrmann.shopping.controller;

import com.fherrmann.shopping.security.TokenAuthFilter;
import com.fherrmann.shopping.security.TokenRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Der Browser bekommt sein Cookie ueber einen Link: {@code /setup?token=…}.
 * Einmal je Geraet, danach ist die Seite einfach da - dasselbe Ritual wie
 * beim Weight Tracker. Die App braucht das nicht, sie schickt den Token als
 * Header.
 */
@RestController
public class SetupController {

    private final TokenRegistry registry;

    public SetupController(TokenRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/setup")
    public ResponseEntity<String> setup(@RequestParam(required = false) String token, HttpServletRequest request) {
        if (registry.nameFor(token).isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .header(HttpHeaders.CONTENT_TYPE, "text/plain;charset=UTF-8")
                    .body("Der Token stimmt nicht.");
        }
        String base = request.getContextPath().isEmpty() ? "/" : request.getContextPath() + "/";
        ResponseCookie cookie = ResponseCookie.from(TokenAuthFilter.COOKIE, token)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(base)
                // Browser kappen Cookies bei 400 Tagen - mehr geht nicht,
                // weniger hiesse alle paar Monate den Link neu oeffnen.
                .maxAge(Duration.ofDays(400))
                .build();
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .header(HttpHeaders.LOCATION, base)
                .build();
    }
}
