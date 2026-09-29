package edu.escuelaing.techcup.shared.security;

import edu.escuelaing.techcup.shared.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies stateless HS256 JSON Web Tokens.
 * Claims: {@code sub} = user id, {@code email}, {@code roles}, {@code jti} = unique token id.
 * Tokens are not stored server side; logout puts the {@code jti} in the {@link TokenDenylist}
 * until the token would have expired anyway.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLES = "roles";

    private final SecretKey key;
    private final Duration expiration;
    private final Clock clock;

    public JwtService(AppProperties properties, Clock clock) {
        this.key = Keys.hmacShaKeyFor(properties.jwt().secret().getBytes(StandardCharsets.UTF_8));
        this.expiration = Duration.ofMinutes(properties.jwt().expirationMinutes());
        this.clock = clock;
    }

    /** A freshly issued token and its absolute expiry. */
    public record IssuedToken(String token, Instant expiresAt) {
    }

    /**
     * The verified content of a token. {@code tokenId} is null only for tokens issued before the
     * {@code jti} claim existed; such tokens cannot be revoked individually.
     */
    public record TokenClaims(Long userId, String email, List<String> roles, String tokenId, Instant expiresAt) {
    }

    public IssuedToken issue(Long userId, String email, Collection<String> roles) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(expiration);
        String token = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_ROLES, List.copyOf(roles))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /** Verifies signature and expiry; returns empty for any malformed, tampered or expired token. */
    public Optional<TokenClaims> parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).clock(() -> Date.from(clock.instant())).build()
                    .parseSignedClaims(token).getPayload();
            Long userId = Long.valueOf(claims.getSubject());
            String email = claims.get(CLAIM_EMAIL, String.class);
            @SuppressWarnings("unchecked")
            List<String> roles = claims.get(CLAIM_ROLES, List.class);
            Instant expiresAt = claims.getExpiration() == null ? null : claims.getExpiration().toInstant();
            return Optional.of(new TokenClaims(userId, email, roles == null ? List.of() : roles,
                    claims.getId(), expiresAt));
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected JWT: {}", ex.getMessage());
            return Optional.empty();
        }
    }
}
