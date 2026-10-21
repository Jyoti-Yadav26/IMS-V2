package com.ims.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class UserOrIpKeyResolverTest {

    private final UserOrIpKeyResolver resolver = new UserOrIpKeyResolver();

    @Test
    void keysOnTheAuthenticatedUser() {
        ServerWebExchange exchange = MockServerWebExchange
                .from(MockServerHttpRequest.get("/api/orders"))
                .mutate()
                .principal(Mono.just(new UsernamePasswordAuthenticationToken(
                        "cust-42", "n/a", AuthorityUtils.createAuthorityList("ROLE_CUSTOMER"))))
                .build();

        assertThat(resolver.resolve(exchange).block()).isEqualTo("user:cust-42");
    }

    @Test
    void fallsBackToTheClientIpWhenAnonymous() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/inventory/products")
                        .remoteAddress(new InetSocketAddress("203.0.113.7", 51000)));

        assertThat(resolver.resolve(exchange).block()).isEqualTo("ip:203.0.113.7");
    }

    @Test
    void fallsBackToUnknownWhenThereIsNoRemoteAddress() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/inventory/products"));

        assertThat(resolver.resolve(exchange).block()).isEqualTo("ip:unknown");
    }

    /**
     * An empty key is not an option: RequestRateLimiter defaults denyEmptyKey to true and answers
     * an empty key with 403, which would look exactly like an authorisation bug.
     */
    @Test
    void neverResolvesToAnEmptyMono() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/orders"));

        assertThat(resolver.resolve(exchange).blockOptional()).isPresent();
    }
}
