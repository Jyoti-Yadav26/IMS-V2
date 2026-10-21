package com.ims.gateway.security;

import com.ims.gateway.config.GatewayAuthProperties;
import com.ims.gateway.config.SecurityConfig;
import com.ims.gateway.dto.LoginResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the encoder and decoder halves agree: a token built by {@link JwtIssuer} with
 * NimbusJwtEncoder + ImmutableSecret + an explicit HS256 header is one that the gateway's
 * NimbusReactiveJwtDecoder accepts and reads the same claims from. Getting either side's algorithm
 * or key wrong fails here rather than as a puzzling 401 in an integration test.
 */
class JwtIssuerTest {

    private static final String SECRET = "unit-test-ims-gateway-hs256-secret-key-0123456789";

    private final SecurityConfig securityConfig = new SecurityConfig();

    @Test
    void issuesATokenTheGatewayDecoderAccepts() {
        GatewayAuthProperties properties = properties(SECRET, Duration.ofMinutes(15));
        JwtIssuer issuer = new JwtIssuer(securityConfig.jwtEncoder(properties), properties);

        LoginResponse response = issuer.issue(authentication("admin1", "ROLE_ADMIN"));

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresInSeconds()).isEqualTo(900);
        // The ROLE_ prefix is Spring Security's internal convention and stays off the wire.
        assertThat(response.roles()).containsExactly("ADMIN");

        ReactiveJwtDecoder decoder = securityConfig.jwtDecoder(properties);
        Jwt decoded = decoder.decode(response.accessToken()).block();

        assertThat(decoded).isNotNull();
        assertThat(decoded.getSubject()).isEqualTo("admin1");
        assertThat(decoded.getIssuer().toString()).isEqualTo("https://ims-gateway");
        assertThat(decoded.getClaimAsStringList("roles")).containsExactly("ADMIN");
        assertThat(Duration.between(decoded.getIssuedAt(), decoded.getExpiresAt()))
                .isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejected() {
        GatewayAuthProperties mine = properties(SECRET, Duration.ofMinutes(15));
        GatewayAuthProperties theirs =
                properties("a-totally-different-secret-key-for-this-test-01", Duration.ofMinutes(15));

        String foreignToken = new JwtIssuer(securityConfig.jwtEncoder(theirs), theirs)
                .issue(authentication("admin1", "ROLE_ADMIN"))
                .accessToken();

        ReactiveJwtDecoder decoder = securityConfig.jwtDecoder(mine);

        assertThatThrownBy(() -> decoder.decode(foreignToken).block())
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    }

    /** HS256 needs a 256-bit key; the explicit check names the env var instead of failing deep in Nimbus. */
    @Test
    void aShortSecretIsRejectedAtStartup() {
        GatewayAuthProperties tooShort = properties("only-16-bytes-ab", Duration.ofMinutes(15));

        assertThatThrownBy(() -> securityConfig.jwtEncoder(tooShort))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    private static GatewayAuthProperties properties(String secret, Duration ttl) {
        return new GatewayAuthProperties(
                new GatewayAuthProperties.Jwt(secret, "https://ims-gateway", ttl), List.of());
    }

    private static Authentication authentication(String name, String authority) {
        return new UsernamePasswordAuthenticationToken(
                name, "n/a", AuthorityUtils.createAuthorityList(authority));
    }
}
