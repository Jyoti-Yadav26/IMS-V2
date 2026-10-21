package com.ims.gateway.security;

import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Reads the authenticated user id off an exchange, for the two gateway components that need it:
 * the header filter and the rate-limiter key resolver.
 *
 * <p>It uses {@code exchange.getPrincipal()} rather than ReactiveSecurityContextHolder because
 * that is what Spring Cloud Gateway's own PrincipalNameKeyResolver and Spring Security's
 * ExceptionTranslationWebFilter do. Spring Security's SecurityContextServerWebExchangeWebFilter
 * mutates the exchange itself, so the principal is available to anything downstream of the
 * security filter chain without depending on Reactor context propagation surviving the gateway's
 * internal operators.
 */
public final class ExchangeUser {

    private ExchangeUser() {
    }

    /**
     * @return the token's subject, or an EMPTY Mono when the request is not authenticated.
     *         Callers must handle empty - an empty Mono silently aborts a reactive chain.
     */
    public static Mono<String> userId(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .filter(Authentication.class::isInstance)
                .cast(Authentication.class)
                .filter(Authentication::isAuthenticated)
                .map(Authentication::getName)
                .filter(StringUtils::hasText);
    }
}
