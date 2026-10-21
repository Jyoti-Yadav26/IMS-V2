package com.ims.gateway.filter;

import com.ims.gateway.security.ExchangeUser;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * Buckets rate-limited requests by authenticated user, falling back to the client IP.
 *
 * <p>Keying on the user id is the point of doing this behind authentication: one noisy customer is
 * throttled without affecting anyone who happens to share their NAT address. The IP fallback only
 * matters for the handful of paths that are reachable without a token.
 *
 * <p>The bean name is referenced from application.yml as "#{@userOrIpKeyResolver}" - that SpEL
 * expression must stay quoted in YAML, or the leading # turns the whole line into a comment and the
 * limiter silently falls back to Spring Cloud Gateway's default PrincipalNameKeyResolver.
 */
@Component("userOrIpKeyResolver")
public class UserOrIpKeyResolver implements KeyResolver {

    private static final String UNKNOWN_IP = "unknown";

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        // Never return an empty Mono: RequestRateLimiter defaults denyEmptyKey to true and answers
        // an empty key with 403, which looks exactly like an authorisation bug.
        return ExchangeUser.userId(exchange)
                .map(userId -> "user:" + userId)
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + clientIp(exchange)));
    }

    /**
     * There is no proxy in front of the gateway locally. Behind a real load balancer this should
     * become XForwardedRemoteAddressResolver.maxTrustedIndex(1), otherwise every anonymous caller
     * shares the load balancer's address and therefore one bucket.
     */
    private String clientIp(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return UNKNOWN_IP;
        }
        return remoteAddress.getAddress().getHostAddress();
    }
}
