package com.ims.gateway.ratelimit;

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

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.reset;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;

/**
 * What actually happens when Redis is unreachable: the limiter fails OPEN.
 *
 * <p>RedisRateLimiter.isAllowed guards the Lua call twice. The inner
 * {@code Flux.onErrorResume} logs "Error calling rate limiter lua" and substitutes a result
 * meaning "allowed"; the outer {@code catch (Exception)} logs "Error determining if user allowed
 * from redis" and returns {@code new Response(true, getHeaders(config, -1L))}. Both paths allow the
 * request and both stamp {@code X-RateLimit-Remaining: -1}, which is why this test asserts on that
 * header rather than only on the status: it pins down which code path ran.
 *
 * <p>The limiter here is the tiny one (replenish 1, burst 2), so without Redis these 20 requests
 * would certainly have been throttled. That they all succeed is the evidence that a limit which
 * would have denied was simply not enforced.
 *
 * <p>Why fail-open is the right default: failing closed would turn a Redis outage into a total API
 * outage, trading the whole platform for an abuse-control feature. Business invariants - stock
 * levels, idempotency, optimistic locking - are enforced in the services regardless.
 *
 * <p>There is no Testcontainers here on purpose, so this one runs on a machine without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"gateway-it", "ratelimit-it", "redis-down-it"})
@AutoConfigureWireMock(port = 0)
class GatewayRateLimiterFailOpenIntegrationTest {

    /**
     * A port that was free a moment ago and is closed again now, so Lettuce gets an immediate
     * connection refusal. Pointing at a blackholed address instead would hang until the timeout.
     * @DynamicPropertySource has the highest precedence, so no profile yml can override this.
     */
    @DynamicPropertySource
    static void deadRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", GatewayRateLimiterFailOpenIntegrationTest::closedPort);
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not find a free port to close", ex);
        }
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
        stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}")));
    }

    @Test
    void requestsAreStillServedWhenRedisIsUnreachable() {
        String token = "Bearer " + JwtTestSupport.customerToken(JwtTestSupport.randomSubject());

        for (int i = 0; i < 20; i++) {
            client.get().uri("/api/orders")
                    .header("Authorization", token)
                    .exchange()
                    .expectStatus().isOk()
                    // The signature of getHeaders(config, -1L) on both fail-open branches.
                    .expectHeader().valueEquals("X-RateLimit-Remaining", "-1");
        }

        // Every one of them really was proxied, not short-circuited by the limiter.
        verify(20, getRequestedFor(urlEqualTo("/api/orders")));
    }

    /** Authentication is unaffected by the outage: fail-open applies to throttling only. */
    @Test
    void authenticationStillAppliesWhenRedisIsUnreachable() {
        client.get().uri("/api/orders").exchange().expectStatus().isUnauthorized();

        client.post().uri("/api/inventory/products/SKU-1/restock?quantity=5")
                .header("Authorization", "Bearer " + JwtTestSupport.customerToken(JwtTestSupport.randomSubject()))
                .exchange()
                .expectStatus().isForbidden();
    }
}
