package com.ims.gateway.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtGrantedAuthoritiesConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.util.Assert;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Authentication and authorisation for the gateway. Nothing downstream changes: the services still
 * trust whatever reaches them, so this chain plus
 * {@link com.ims.gateway.filter.UserIdHeaderGlobalFilter} is the only thing between a client and
 * the business endpoints.
 *
 * <p>The gateway is WebFlux, so this is all reactive: SecurityWebFilterChain rather than
 * HttpSecurity, ReactiveJwtDecoder rather than JwtDecoder, MapReactiveUserDetailsService rather
 * than InMemoryUserDetailsManager.
 *
 * <p>HS256 with a shared secret is a deliberate demo trade-off: the gateway is both issuer and
 * verifier. A real deployment points
 * spring.security.oauth2.resourceserver.jwt.jwk-set-uri at an identity provider such as Keycloak
 * and verifies RS256 with a public key, so nothing but the IdP can mint a token.
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(GatewayAuthProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            ReactiveJwtDecoder jwtDecoder,
            ReactiveJwtAuthenticationConverter jwtAuthenticationConverter) {
        return http
                // Stateless bearer-token API. Reactive security enables CSRF by default, which
                // would reject every POST - including /auth/login - with a 403 and no useful
                // explanation. There is no cookie or session here to protect.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                // No web session for an API request, and nothing saved for a post-login redirect.
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.POST, "/auth/login").permitAll()
                        // Only health is reachable. See management.endpoints.web.exposure in
                        // application.yml, which no longer exposes info or the gateway endpoint.
                        .pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .pathMatchers("/actuator/**").denyAll()
                        // Internal saga endpoint: order-service calls it over Feign, so nothing
                        // legitimate reaches it through the gateway. Denied even for ADMIN, and
                        // additionally not matched by any route (see application.yml).
                        .pathMatchers("/api/inventory/reserve", "/api/inventory/reserve/**").denyAll()
                        .pathMatchers(HttpMethod.POST, "/api/inventory/products").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.POST, "/api/inventory/products/*/restock").hasRole("ADMIN")
                        // Notification rows are not user-scoped (the entity has no userId), so a
                        // CUSTOMER token would otherwise read every other customer's order events.
                        .pathMatchers("/api/notifications/**").hasRole("ADMIN")
                        // -------------------------------------------------------------------
                        // Catalogue reads require a token: ProductResponse exposes quantity and
                        // reorderThreshold, which is internal stock data, and an authenticated
                        // caller also gives the rate limiter a stable key instead of an IP.
                        // To make reads public, uncomment this ONE line (and flip the
                        // expectation in GatewayAuthIntegrationTest#productReadRequiresToken):
                        // .pathMatchers(HttpMethod.GET, "/api/inventory/products", "/api/inventory/products/*").permitAll()
                        // -------------------------------------------------------------------
                        // Everything else under /api needs a valid token. Covers /api/orders/**
                        // and the catalogue reads above.
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtDecoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }

    /**
     * Boot has no property for a symmetric HS256 secret - OAuth2ResourceServerProperties offers
     * only jwk-set-uri, public-key-location and issuer-uri - so the decoder is built by hand. The
     * default validator set includes JwtTimestampValidator, which is what rejects an expired token
     * (allowing 60s of clock skew by default).
     */
    @Bean
    public ReactiveJwtDecoder jwtDecoder(GatewayAuthProperties properties) {
        return NimbusReactiveJwtDecoder.withSecretKey(hmacKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    public JwtEncoder jwtEncoder(GatewayAuthProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(hmacKey(properties)));
    }

    /**
     * Maps the token's "roles" claim onto ROLE_-prefixed authorities so hasRole("ADMIN") works.
     * Both setters are required: the converter defaults to the "scope"/"scp" claim with a "SCOPE_"
     * prefix, and leaving either at its default makes every hasRole check fail silently.
     */
    @Bean
    public ReactiveJwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");

        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                new ReactiveJwtGrantedAuthoritiesConverterAdapter(authorities));
        // principalClaimName defaults to "sub", so Authentication#getName() is the user id that
        // UserIdHeaderGlobalFilter asserts downstream as X-User-Id.
        return converter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** The two demo accounts behind /auth/login. Passwords are BCrypt hashes from configuration. */
    @Bean
    public MapReactiveUserDetailsService demoUserDetailsService(GatewayAuthProperties properties) {
        List<UserDetails> users = properties.users().stream()
                .map(user -> (UserDetails) User.withUsername(user.username())
                        .password(user.passwordHash())
                        .roles(user.roles().toArray(String[]::new))
                        .build())
                .toList();
        return new MapReactiveUserDetailsService(users);
    }

    /**
     * HS256 needs at least a 256-bit key; Nimbus throws on anything shorter. Checking it here turns
     * a confusing failure deep inside the encoder into an explicit startup error naming the env var.
     */
    private static SecretKey hmacKey(GatewayAuthProperties properties) {
        byte[] secret = properties.jwt().secret().getBytes(StandardCharsets.UTF_8);
        Assert.isTrue(secret.length >= 32,
                "ims.auth.jwt.secret (env JWT_SECRET) must be at least 32 bytes for HS256, was "
                        + secret.length);
        return new SecretKeySpec(secret, "HmacSHA256");
    }
}
