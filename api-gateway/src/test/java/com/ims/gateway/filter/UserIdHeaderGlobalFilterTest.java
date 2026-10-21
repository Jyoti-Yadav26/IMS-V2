package com.ims.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the header-hygiene rules, including the two traps that an end-to-end test
 * would only catch by accident: the empty principal Mono, and header case.
 */
class UserIdHeaderGlobalFilterTest {

    private final UserIdHeaderGlobalFilter filter = new UserIdHeaderGlobalFilter();

    @Test
    void replacesASpoofedUserIdWithTheAuthenticatedName() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/orders")
                        .header("X-User-Id", "attacker")
                        .header("X-User-Roles", "ADMIN"));

        ServerHttpRequest routed = runFilter(authenticated(exchange, "cust-42"));

        assertThat(routed.getHeaders().getFirst("X-User-Id")).isEqualTo("cust-42");
        assertThat(routed.getHeaders().containsKey("X-User-Roles")).isFalse();
    }

    /**
     * A spoofed header sent in a different case must not survive alongside the real one. HttpHeaders
     * is case-insensitive, so the assertion that matters is that exactly ONE value remains: a filter
     * that only did headers.set(..) without removing first would leave two, and whichever the
     * downstream service read first would decide the caller's identity.
     */
    @Test
    void removesIdentityHeadersWhateverTheirCase() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/orders")
                        .header("x-uSeR-Id", "attacker")
                        .header("X-USER-ROLES", "ADMIN"));

        ServerHttpRequest routed = runFilter(authenticated(exchange, "cust-7"));

        assertThat(routed.getHeaders().get("X-User-Id")).containsExactly("cust-7");
        assertThat(routed.getHeaders().get("X-User-Roles")).isNull();
    }

    /**
     * The regression guard for defaultIfEmpty: exchange.getPrincipal() is an EMPTY Mono for an
     * unauthenticated request, so a filter written without a default never subscribes the chain.
     * This asserts both that the chain ran and that the spoofed header is gone anyway.
     */
    @Test
    void stripsHeadersAndStillInvokesTheChainWhenUnauthenticated() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health")
                        .header("X-User-Id", "attacker"));

        AtomicReference<ServerWebExchange> seen = new AtomicReference<>();
        GatewayFilterChain chain = routed -> {
            seen.set(routed);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(seen.get()).as("the filter chain must still be invoked").isNotNull();
        assertThat(seen.get().getRequest().getHeaders().containsKey("X-User-Id")).isFalse();
    }

    @Test
    void runsImmediatelyBeforeTheRoutingFilters() {
        assertThat(filter.getOrder()).isEqualTo(Integer.MAX_VALUE - 1);
    }

    private ServerWebExchange authenticated(MockServerWebExchange exchange, String name) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                name, "n/a", AuthorityUtils.createAuthorityList("ROLE_CUSTOMER"));
        return exchange.mutate().principal(Mono.just(authentication)).build();
    }

    private ServerHttpRequest runFilter(ServerWebExchange exchange) {
        AtomicReference<ServerHttpRequest> seen = new AtomicReference<>();
        GatewayFilterChain chain = routed -> {
            seen.set(routed.getRequest());
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        return seen.get();
    }
}
