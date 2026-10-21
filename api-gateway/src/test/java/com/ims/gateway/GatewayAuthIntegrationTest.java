package com.ims.gateway;

import com.ims.gateway.dto.LoginResponse;
import com.ims.gateway.support.JwtTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.reset;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway's authentication, authorisation and identity-header behaviour, exercised end to end:
 * a real reactive security chain, the real route table from src/main/resources/application.yml, a
 * real Redis for the rate limiter and WireMock standing in for the downstream services.
 *
 * <p>Because this runs against the production configuration, {@code adminCanRestock} proves rather
 * a lot at once: the lb:// route resolves through Spring Cloud LoadBalancer, the JWT is decoded
 * with the configured HS256 key, and the "roles" claim really does become a ROLE_ authority that
 * hasRole("ADMIN") matches.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("gateway-it")
@AutoConfigureWireMock(port = 0)
class GatewayAuthIntegrationTest {

    /**
     * Each integration class owns its own container rather than sharing one via an abstract base:
     * TestcontainersExtension stops static containers in afterAll, so a second class reusing a
     * cached Spring context would point at a container that no longer exists.
     */
    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @LocalServerPort
    int gatewayPort;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + gatewayPort)
                .responseTimeout(Duration.ofSeconds(20))
                .build();
        reset();
        // Catch-all so that anything which legitimately reaches a downstream service gets a
        // response; the assertions are about what does and does not arrive, not about payloads.
        stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}")));
    }

    // ---------------------------------------------------------------- authentication

    @Test
    void noTokenIsUnauthorized() {
        client.get().uri("/api/orders").exchange().expectStatus().isUnauthorized();
        verify(0, getRequestedFor(urlEqualTo("/api/orders")));
    }

    @Test
    void tokenWithBadSignatureIsUnauthorized() {
        client.get().uri("/api/orders")
                .header("Authorization", "Bearer " + JwtTestSupport.wrongKeyToken(JwtTestSupport.randomSubject()))
                .exchange()
                .expectStatus().isUnauthorized();
        verify(0, getRequestedFor(urlEqualTo("/api/orders")));
    }

    @Test
    void expiredTokenIsUnauthorized() {
        client.get().uri("/api/orders")
                .header("Authorization", "Bearer " + JwtTestSupport.expiredToken(JwtTestSupport.randomSubject()))
                .exchange()
                .expectStatus().isUnauthorized();
        verify(0, getRequestedFor(urlEqualTo("/api/orders")));
    }

    @Test
    void garbageTokenIsUnauthorized() {
        client.get().uri("/api/orders")
                .header("Authorization", "Bearer not-a-jwt")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void customerCanPlaceOrders() {
        client.post().uri("/api/orders")
                .header("Authorization", "Bearer " + JwtTestSupport.customerToken(JwtTestSupport.randomSubject()))
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk();
        verify(1, postRequestedFor(urlEqualTo("/api/orders")));
    }

    // ---------------------------------------------------------------- role rules

    @Test
    void customerCannotRestock() {
        client.post().uri("/api/inventory/products/SKU-1/restock?quantity=5")
                .header("Authorization", "Bearer " + JwtTestSupport.customerToken(JwtTestSupport.randomSubject()))
                .exchange()
                .expectStatus().isForbidden();
        verify(0, postRequestedFor(urlPathMatching("/api/inventory/products/.*/restock")));
    }

    @Test
    void adminCanRestock() {
        client.post().uri("/api/inventory/products/SKU-1/restock?quantity=5")
                .header("Authorization", "Bearer " + JwtTestSupport.adminToken(JwtTestSupport.randomSubject()))
                .exchange()
                .expectStatus().isOk();
        verify(1, postRequestedFor(urlPathMatching("/api/inventory/products/.*/restock")));
    }

    @Test
    void customerCannotCreateProducts() {
        client.post().uri("/api/inventory/products")
                .header("Authorization", "Bearer " + JwtTestSupport.customerToken(JwtTestSupport.randomSubject()))
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isForbidden();
        verify(0, postRequestedFor(urlEqualTo("/api/inventory/products")));
    }

    @Test
    void adminCanCreateProducts() {
        client.post().uri("/api/inventory/products")
                .header("Authorization", "Bearer " + JwtTestSupport.adminToken(JwtTestSupport.randomSubject()))
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk();
        verify(1, postRequestedFor(urlEqualTo("/api/inventory/products")));
    }

    @Test
    void customerCannotReadNotifications() {
        client.get().uri("/api/notifications")
                .header("Authorization", "Bearer " + JwtTestSupport.customerToken(JwtTestSupport.randomSubject()))
                .exchange()
                .expectStatus().isForbidden();
        verify(0, getRequestedFor(urlEqualTo("/api/notifications")));
    }

    /** The other half of the rule: locking notifications down must not lock ADMIN out of them. */
    @Test
    void adminCanReadNotifications() {
        client.get().uri("/api/notifications")
                .header("Authorization", "Bearer " + JwtTestSupport.adminToken(JwtTestSupport.randomSubject()))
                .exchange()
                .expectStatus().isOk();
        verify(1, getRequestedFor(urlEqualTo("/api/notifications")));
    }

    /**
     * Catalogue reads are authenticated, not public, because ProductResponse exposes quantity and
     * reorderThreshold. If the commented "flip point" line in SecurityConfig is ever uncommented,
     * the first expectation here becomes isOk().
     */
    @Test
    void productReadRequiresToken() {
        client.get().uri("/api/inventory/products").exchange().expectStatus().isUnauthorized();

        client.get().uri("/api/inventory/products")
                .header("Authorization", "Bearer " + JwtTestSupport.customerToken(JwtTestSupport.randomSubject()))
                .exchange()
                .expectStatus().isOk();
    }

    // ---------------------------------------------------------------- the internal saga endpoint

    /**
     * Blocked twice over: denyAll in the security chain, and no route predicate matches the path.
     * Security runs first (WebFilterChainProxy is ordered -100, well before the gateway's
     * DispatcherHandler), so the observable status is 403 and not the 404 the route table alone
     * would produce.
     */
    @Test
    void reserveIsBlockedEvenForAdmin() {
        client.post().uri("/api/inventory/reserve")
                .header("Authorization", "Bearer " + JwtTestSupport.adminToken(JwtTestSupport.randomSubject()))
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isForbidden();
        verify(0, postRequestedFor(urlEqualTo("/api/inventory/reserve")));
    }

    @Test
    void reserveIsBlockedWithoutToken() {
        client.post().uri("/api/inventory/reserve")
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isUnauthorized();
        verify(0, postRequestedFor(urlEqualTo("/api/inventory/reserve")));
    }

    // ---------------------------------------------------------------- identity headers

    /**
     * The header is sent in mixed case on purpose: HttpHeaders over Netty is case-insensitive, so a
     * naive remove("X-User-Id") would appear to work while leaving a spoofed "x-uSeR-Id" in place.
     */
    @Test
    void spoofedUserHeadersAreReplacedByTheTokenSubject() {
        String subject = JwtTestSupport.randomSubject();

        client.post().uri("/api/orders")
                .header("Authorization", "Bearer " + JwtTestSupport.customerToken(subject))
                .header("x-uSeR-Id", "attacker")
                .header("X-User-Roles", "ADMIN")
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk();

        verify(postRequestedFor(urlEqualTo("/api/orders"))
                .withHeader("X-User-Id", equalTo(subject))
                .withoutHeader("X-User-Roles"));
    }

    // ---------------------------------------------------------------- actuator

    /**
     * Also the regression guard for the global filter's defaultIfEmpty: an unauthenticated request
     * has an empty principal Mono, and without the default the filter chain would never be
     * subscribed and this request would never complete.
     */
    @Test
    void onlyHealthIsExposedOnTheActuator() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();

        // Not exposed any more (include: health), and denied by the security chain regardless.
        client.get().uri("/actuator/gateway/routes").exchange()
                .expectStatus().value(status -> assertThat(status).isIn(401, 403, 404));
        client.get().uri("/actuator/env").exchange()
                .expectStatus().value(status -> assertThat(status).isIn(401, 403, 404));
    }

    // ---------------------------------------------------------------- demo login

    /**
     * The one test that closes the loop: a token this gateway issued is accepted by this gateway's
     * resource server, which is what proves the JwtEncoder and ReactiveJwtDecoder beans agree on
     * the key and the algorithm.
     */
    @Test
    void loginReturnsAUsableTokenForTheDemoCustomer() {
        LoginResponse login = client.post().uri("/auth/login")
                .header("Content-Type", "application/json")
                .bodyValue("{\"username\":\"customer1\",\"password\":\"it-customer-pw\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(LoginResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(login).isNotNull();
        assertThat(login.tokenType()).isEqualTo("Bearer");
        assertThat(login.roles()).containsExactly("CUSTOMER");
        assertThat(login.expiresInSeconds()).isEqualTo(900);
        assertThat(login.accessToken()).isNotBlank();

        client.get().uri("/api/orders")
                .header("Authorization", "Bearer " + login.accessToken())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void loginAsAdminYieldsTheAdminRole() {
        client.post().uri("/auth/login")
                .header("Content-Type", "application/json")
                .bodyValue("{\"username\":\"admin1\",\"password\":\"it-admin-pw\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.roles[0]").isEqualTo("ADMIN");
    }

    @Test
    void loginWithWrongPasswordIsUnauthorized() {
        client.post().uri("/auth/login")
                .header("Content-Type", "application/json")
                .bodyValue("{\"username\":\"customer1\",\"password\":\"wrong\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void loginWithUnknownUserIsUnauthorized() {
        client.post().uri("/auth/login")
                .header("Content-Type", "application/json")
                .bodyValue("{\"username\":\"nobody\",\"password\":\"it-customer-pw\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void loginWithEmptyBodyIsUnauthorizedNotAServerError() {
        client.post().uri("/auth/login")
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
