package edu.escuelaing.techcup.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import edu.escuelaing.techcup.shared.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tokens carry a unique id and an expiry the parser enforces. */
class JwtServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T10:00:00Z");
    private static final AppProperties PROPERTIES = new AppProperties(
            new AppProperties.Jwt("unit-test-secret-that-is-long-enough-for-hs256-0123456789", 60),
            List.of("http://localhost:5173"),
            "UTC",
            new AppProperties.Storage(1024),
            new AppProperties.Bootstrap("admin@escuelaing.edu.co", "Str0ngAdminPass"),
            new AppProperties.Player(5, 100));

    @Test
    void issuedTokensCarryAUniqueIdAndTheirExpiry() {
        JwtService service = new JwtService(PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC));

        JwtService.IssuedToken first = service.issue(10L, "ana@escuelaing.edu.co", List.of("PLAYER"));
        JwtService.IssuedToken second = service.issue(10L, "ana@escuelaing.edu.co", List.of("PLAYER"));

        JwtService.TokenClaims claims = service.parse(first.token()).orElseThrow();
        assertThat(claims.userId()).isEqualTo(10L);
        assertThat(claims.email()).isEqualTo("ana@escuelaing.edu.co");
        assertThat(claims.roles()).containsExactly("PLAYER");
        assertThat(claims.tokenId()).isNotBlank();
        assertThat(claims.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(60)));
        assertThat(service.parse(second.token()).orElseThrow().tokenId()).isNotEqualTo(claims.tokenId());
    }

    @Test
    void expiredTokensAreRejected() {
        JwtService issuer = new JwtService(PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC));
        JwtService later = new JwtService(PROPERTIES, Clock.fixed(NOW.plus(Duration.ofMinutes(61)), ZoneOffset.UTC));
        String token = issuer.issue(10L, "ana@escuelaing.edu.co", List.of("PLAYER")).token();

        assertThat(issuer.parse(token)).isPresent();
        assertThat(later.parse(token)).isEmpty();
    }

    @Test
    void tamperedTokensAreRejected() {
        JwtService service = new JwtService(PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC));
        String token = service.issue(10L, "ana@escuelaing.edu.co", List.of("PLAYER")).token();
        String[] parts = token.split("\\.");
        String tamperedPayload = (parts[1].charAt(0) == 'e' ? 'f' : 'e') + parts[1].substring(1);

        assertThat(service.parse(parts[0] + "." + tamperedPayload + "." + parts[2])).isEmpty();
        assertThat(service.parse("not-a-jwt")).isEmpty();
    }
}
