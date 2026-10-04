package com.paytm.reservation.controller;

import com.paytm.reservation.service.JwtService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@ConditionalOnProperty(
        name = "app.auth.token-endpoint-enabled",
        havingValue = "true"
)
public class AuthController {

    private final JwtService jwtService;

    public AuthController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @PostMapping("/token")
    public String generateToken(
            @RequestParam String userId,
            @RequestParam String role
    ) {
        return jwtService.generateToken(userId, role);
    }
}