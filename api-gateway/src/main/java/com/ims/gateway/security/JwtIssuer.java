package com.ims.gateway.security;

import com.ims.gateway.config.GatewayAuthProperties;
import com.ims.gateway.dto.LoginResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Signs the HS256 tokens handed out by the demo login endpoint.
 *
 * <p>The claims are deliberately minimal: {@code sub} is the user id that the gateway later asserts
 * downstream as X-User-Id, and {@code roles} carries the authorities WITHOUT the "ROLE_" prefix -
 * the prefix is Spring Security's internal convention and is re-added on the way in by the
 * authorities converter in {@link com.ims.gateway.config.SecurityConfig}.
 *
 * <p>Signing is pure CPU work (an HMAC over a few hundred bytes), so doing it on the event loop
 * does not block the reactive pipeline.
 */
@Component
@RequiredArgsConstructor
public class JwtIssuer {

    /** Spring Security's "ROLE_" prefix is an implementation detail and stays off the wire. */
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtEncoder jwtEncoder;
    private final GatewayAuthProperties properties;

    public LoginResponse issue(Authentication authentication) {
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(authority -> authority.startsWith(ROLE_PREFIX)
                        ? authority.substring(ROLE_PREFIX.length())
                        : authority)
                .toList();

        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.jwt().ttl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .subject(authentication.getName())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("roles", roles)
                .build();

        // The algorithm has to be stated explicitly: JwtEncoderParameters.from(claims) alone
        // defaults to RS256 and NimbusJwtEncoder then fails, because our key is symmetric.
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new LoginResponse(token, "Bearer", properties.jwt().ttl().toSeconds(), roles);
    }
}
