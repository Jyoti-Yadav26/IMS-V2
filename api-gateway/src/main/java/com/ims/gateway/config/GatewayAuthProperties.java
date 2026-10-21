package com.ims.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Gateway authentication settings, bound from the "ims.auth" prefix.
 *
 * <p>Records give constructor binding for free and match the DTO convention used across the other
 * services. The signing secret has no default in application.yml: a missing JWT_SECRET must break
 * startup loudly rather than silently fall back to something guessable.
 *
 * <p>{@code users} is DEMO ONLY - see {@link com.ims.gateway.controller.AuthController}.
 */
@ConfigurationProperties(prefix = "ims.auth")
public record GatewayAuthProperties(Jwt jwt, List<User> users) {

    public record Jwt(String secret, String issuer, Duration ttl) {
    }

    /** {@code passwordHash} is a BCrypt hash, never a plaintext password. */
    public record User(String username, String passwordHash, List<String> roles) {
    }
}
