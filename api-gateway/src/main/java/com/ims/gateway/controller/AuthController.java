package com.ims.gateway.controller;

import com.ims.gateway.dto.LoginRequest;
import com.ims.gateway.dto.LoginResponse;
import com.ims.gateway.security.JwtIssuer;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * DEMO login endpoint. It exists so the platform can be exercised end to end without standing up an
 * identity provider, and it is not a production design: there are two hard-coded accounts, tokens
 * cannot be revoked, there is no refresh token, no account lockout, and /auth/login is not itself
 * rate limited (it is not a gateway route, so default-filters do not apply to it). A real deployment
 * deletes this class and points the resource server at an IdP such as Keycloak.
 *
 * <p>An @RestController can coexist with the gateway's routes because RoutePredicateHandlerMapping
 * is registered at order 1 while RequestMappingHandlerMapping is at order 0, so this mapping is
 * matched first. /auth/login is not matched by any route predicate anyway.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final ReactiveAuthenticationManager authenticationManager;
    private final JwtIssuer jwtIssuer;

    /**
     * The authentication manager is built here instead of being exposed as a bean, which is also
     * why this class has an explicit constructor rather than the usual @RequiredArgsConstructor:
     * ServerHttpSecurity picks up a ReactiveAuthenticationManager bean if one exists, and a
     * username/password manager has no business anywhere near the bearer-token chain.
     */
    public AuthController(ReactiveUserDetailsService userDetailsService,
                          PasswordEncoder passwordEncoder,
                          JwtIssuer jwtIssuer) {
        UserDetailsRepositoryReactiveAuthenticationManager manager =
                new UserDetailsRepositoryReactiveAuthenticationManager(userDetailsService);
        manager.setPasswordEncoder(passwordEncoder);
        this.authenticationManager = manager;
        this.jwtIssuer = jwtIssuer;
    }

    @PostMapping("/login")
    public Mono<LoginResponse> login(@RequestBody LoginRequest request) {
        // Checked before the manager sees it: MapReactiveUserDetailsService lower-cases the
        // username and would NPE into a 500 on a body like {}, which is a client error, not ours.
        if (!StringUtils.hasText(request.username()) || !StringUtils.hasText(request.password())) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        }
        return authenticationManager
                .authenticate(new UsernamePasswordAuthenticationToken(request.username(), request.password()))
                .map(jwtIssuer::issue)
                // Unknown user and wrong password are both AuthenticationExceptions, so they
                // collapse into the same 401: no user enumeration from the response.
                .onErrorMap(AuthenticationException.class,
                        ex -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
    }
}
