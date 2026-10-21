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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.reset;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the RedisRateLimiter over its limit with a deliberately tiny bucket (replenish 1/s,
 * burst 2 - see application-ratelimit-it.yml).
 *
 * <p>This is the test that proves the "#{@userOrIpKeyResolver}" SpEL in application.yml actually
 * resolves to our bean: a unit test of the resolver cannot, because if that YAML value were
 * unquoted and swallowed as a comment the limiter would silently fall back to Spring Cloud
 * Gateway's default PrincipalNameKeyResolver and still look roughly correct.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"gateway-it", "ratelimit-it"})
@AutoConfigureWireMock(port = 0)
class GatewayRateLimitIntegrationTest {

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
        stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}")));
    }

    @Test
    void exceedingTheLimitIsRejectedWith429() {
        String token = "Bearer " + JwtTestSupport.customerToken(JwtTestSupport.randomSubject());

        int allowed = 0;
        int throttled = 0;
        for (int i = 0; i < 30; i++) {
            int status = client.get().uri("/api/orders")
                    .header("Authorization", token)
                    .exchange()
                    .returnResult(String.class)
                    .getStatus()
                    .value();
            if (status == 429) {
                throttled++;
            } else if (status == 200) {
                allowed++;
            } else {
                throw new AssertionError("Unexpected status from the gateway: " + status);
            }
        }

        assertThat(throttled).as("some requests must be throttled").isGreaterThan(0);
        // Burst capacity is 2, plus whatever the 1/s replenish rate adds while the loop runs.
        // The upper bound is loose on purpose; the point is that it is nowhere near 30.
        assertThat(allowed).as("allowed requests").isBetween(1, 10);
    }

    @Test
    void theThrottledResponseCarriesRateLimitHeaders() {
        String token = "Bearer " + JwtTestSupport.customerToken(JwtTestSupport.randomSubject());

        // Burn the bucket, then assert on the first rejection.
        for (int i = 0; i < 10; i++) {
            client.get().uri("/api/orders").header("Authorization", token).exchange();
        }

        client.get().uri("/api/orders")
                .header("Authorization", token)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists("X-RateLimit-Remaining");
    }

    /**
     * Two different subjects get two different buckets, which is the whole reason for keying on the
     * token rather than the IP: both callers here share 127.0.0.1.
     */
    @Test
    void eachUserGetsTheirOwnBucket() {
        String noisy = "Bearer " + JwtTestSupport.customerToken(JwtTestSupport.randomSubject());
        String quiet = "Bearer " + JwtTestSupport.customerToken(JwtTestSupport.randomSubject());

        for (int i = 0; i < 10; i++) {
            client.get().uri("/api/orders").header("Authorization", noisy).exchange();
        }

        client.get().uri("/api/orders")
                .header("Authorization", quiet)
                .exchange()
                .expectStatus().isOk();
    }
}
