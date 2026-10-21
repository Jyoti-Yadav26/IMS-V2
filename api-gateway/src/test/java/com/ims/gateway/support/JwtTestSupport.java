package com.ims.gateway.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Mints tokens for the integration tests with raw Nimbus, so the negative cases never go through
 * production code: a token signed by {@link #wrongKeyToken} could not be produced by JwtIssuer at
 * all, which is exactly what makes it a useful test input.
 *
 * <p>Named *Support rather than *Test so surefire does not try to run it.
 *
 * <p>Every method takes the subject and callers pass a fresh one via {@link #randomSubject()}. That
 * is the whole answer to cross-test rate-limit interference: the limiter buckets on the subject
 * (see UserOrIpKeyResolver), and the resource server validates a signature without any user
 * lookup, so each test method gets a private bucket for free.
 */
public final class JwtTestSupport {

    /** Must stay in sync with ims.auth.jwt.secret in application-gateway-it.yml. */
    public static final String SECRET = "it-only-ims-gateway-hs256-secret-key-0123456789";

    private static final String OTHER_SECRET = "a-different-key-that-the-gateway-does-not-trust-01";
    private static final String ISSUER = "https://ims-gateway";

    private JwtTestSupport() {
    }

    public static String randomSubject() {
        return "it-" + UUID.randomUUID();
    }

    public static String customerToken(String subject) {
        return token(subject, List.of("CUSTOMER"), SECRET, Instant.now().plus(5, ChronoUnit.MINUTES));
    }

    public static String adminToken(String subject) {
        return token(subject, List.of("ADMIN"), SECRET, Instant.now().plus(5, ChronoUnit.MINUTES));
    }

    /**
     * Expiry is set ten minutes in the past, not one second: JwtTimestampValidator allows 60s of
     * clock skew by default, so a marginally stale token still validates and the test would pass
     * for the wrong reason.
     */
    public static String expiredToken(String subject) {
        return token(subject, List.of("CUSTOMER"), SECRET, Instant.now().minus(10, ChronoUnit.MINUTES));
    }

    /** Structurally valid, correctly shaped claims, signed with a key the gateway does not hold. */
    public static String wrongKeyToken(String subject) {
        return token(subject, List.of("ADMIN"), OTHER_SECRET, Instant.now().plus(5, ChronoUnit.MINUTES));
    }

    private static String token(String subject, List<String> roles, String secret, Instant expiresAt) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(ISSUER)
                    .claim("roles", roles)
                    .issueTime(Date.from(expiresAt.minus(15, ChronoUnit.MINUTES)))
                    .expirationTime(Date.from(expiresAt))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException("Could not sign a test token", ex);
        }
    }
}
