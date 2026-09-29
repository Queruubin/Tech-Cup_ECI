package edu.escuelaing.techcup.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Revoked tokens stay revoked until their own expiry, then are forgotten. */
class TokenDenylistTest {

    private static final Instant NOW = Instant.parse("2026-03-10T10:00:00Z");

    @Test
    void aDeniedTokenIsRejectedUntilItExpires() {
        TokenDenylist denylist = new TokenDenylist(Clock.fixed(NOW, ZoneOffset.UTC));

        denylist.deny("token-1", NOW.plus(Duration.ofHours(1)));

        assertThat(denylist.isDenied("token-1")).isTrue();
        assertThat(denylist.isDenied("token-2")).isFalse();
    }

    @Test
    void expiredEntriesAreForgotten() {
        TokenDenylist denylist = new TokenDenylist(Clock.fixed(NOW.plus(Duration.ofHours(2)), ZoneOffset.UTC));

        denylist.deny("token-1", NOW.plus(Duration.ofHours(1)));

        assertThat(denylist.isDenied("token-1")).isFalse();
        assertThat(denylist.size()).isZero();
    }

    @Test
    void legacyTokensWithoutAnIdAreIgnored() {
        TokenDenylist denylist = new TokenDenylist(Clock.fixed(NOW, ZoneOffset.UTC));

        denylist.deny(null, NOW.plus(Duration.ofHours(1)));

        assertThat(denylist.isDenied(null)).isFalse();
        assertThat(denylist.size()).isZero();
    }
}
