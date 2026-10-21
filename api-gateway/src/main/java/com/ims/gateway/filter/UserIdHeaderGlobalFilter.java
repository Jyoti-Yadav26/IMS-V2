package com.ims.gateway.filter;

import com.ims.gateway.security.ExchangeUser;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Replaces the caller's identity headers with the gateway's own assertion.
 *
 * <p>X-User-Id is an assertion of identity, and it is only worth anything if the one component that
 * validated the token is the only component that can write it. If the gateway merely added the
 * header without first removing the client's version, a caller could send
 * {@code X-User-Id: someone-else} and - depending on which value survived - Step 14's cart-service
 * would read another user's cart. So both X-User-Id and X-User-Roles are stripped unconditionally,
 * on every route, and X-User-Id is then set from the verified token subject. Downstream services
 * stay simple: they trust the header and never parse a token.
 *
 * <p>X-User-Roles is stripped but not re-added - nothing downstream consumes roles yet, and a
 * header nobody reads is just another thing to keep in sync.
 *
 * <p>This is a GlobalFilter rather than per-route RemoveRequestHeader/AddRequestHeader filters for
 * three reasons: AddRequestHeader's value goes through ServerWebExchangeUtils.expand, which expands
 * route URI template variables only and so cannot reach the authentication; a global filter cannot
 * be forgotten when Step 13 and Step 14 add the payment and cart routes; and keeping strip-then-set
 * in one method makes the ordering impossible to get wrong.
 */
@Component
public class UserIdHeaderGlobalFilter implements GlobalFilter, Ordered {

    static final String USER_ID_HEADER = "X-User-Id";
    static final String USER_ROLES_HEADER = "X-User-Roles";

    /** Sentinel for "no authenticated user", so the reactive chain stays non-empty. */
    private static final String ANONYMOUS = "";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ExchangeUser.userId(exchange)
                // ExchangeUser returns an EMPTY Mono for an unauthenticated request (for example
                // the public /actuator/health). Without defaultIfEmpty the flatMap below never
                // runs, the chain is never subscribed, and the response simply ends.
                .defaultIfEmpty(ANONYMOUS)
                .flatMap(userId -> chain.filter(withUserContext(exchange, userId)));
    }

    private ServerWebExchange withUserContext(ServerWebExchange exchange, String userId) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    removeSpoofedHeaders(headers);
                    if (!ANONYMOUS.equals(userId)) {
                        headers.set(USER_ID_HEADER, userId);
                    }
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    /**
     * Removes the identity headers whatever their casing. HttpHeaders over Netty is
     * case-insensitive, but the MultiValueMap behind a mocked request is not, so matching by
     * equalsIgnoreCase is the only form that behaves the same in production and in a unit test.
     * The keys are collected first because removing while iterating the key set would throw.
     */
    private void removeSpoofedHeaders(HttpHeaders headers) {
        List<String> spoofed = headers.keySet().stream()
                .filter(name -> name.equalsIgnoreCase(USER_ID_HEADER)
                        || name.equalsIgnoreCase(USER_ROLES_HEADER))
                .toList();
        spoofed.forEach(headers::remove);
    }

    /**
     * One step before the routing filters (NettyRoutingFilter and ForwardRoutingFilter both sit at
     * LOWEST_PRECEDENCE), so no other filter can re-add the header after us and the mutated request
     * is what actually gets proxied.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }
}
