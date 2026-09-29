package edu.escuelaing.techcup.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates the bearer token on every request (signature, expiry, not revoked by logout) and,
 * when the user still exists and is ACTIVE, populates the security context. Any failure leaves
 * the request anonymous so that protected endpoints answer 401 through the entry point.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final TokenDenylist denylist;
    private final AuthenticatedUserLoader userLoader;

    public JwtAuthenticationFilter(JwtService jwtService, TokenDenylist denylist, AuthenticatedUserLoader userLoader) {
        this.jwtService = jwtService;
        this.denylist = denylist;
        this.userLoader = userLoader;
    }

    /** The raw token of a {@code Bearer} authorization header, or empty when there is none. */
    public static Optional<String> bearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION))
                    .flatMap(jwtService::parse)
                    .filter(claims -> !denylist.isDenied(claims.tokenId()))
                    .flatMap(claims -> userLoader.loadActiveUser(claims.userId()))
                    .ifPresent(user -> authenticate(user, request));
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(AuthenticatedUser user, HttpServletRequest request) {
        List<GrantedAuthority> authorities = user.roles().stream()
                .map(Roles::authority)
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
        var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
