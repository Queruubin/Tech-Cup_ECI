package edu.escuelaing.techcup.shared.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Tokens revoked by logout, keyed by their {@code jti} claim and kept only until the token would
 * have expired on its own. In-memory on purpose: the platform runs as a single instance, and a
 * restart simply forgets revocations of tokens that are about to expire anyway.
 */
@Component
public class TokenDenylist {

    private final Map<String, Instant> deniedUntil = new ConcurrentHashMap<>();
    private final Clock clock;

    public TokenDenylist(Clock clock) {
        this.clock = clock;
    }

    /** Revokes a token until {@code expiresAt}; a null id (legacy token) or a past expiry is ignored. */
    public void deny(String tokenId, Instant expiresAt) {
        purgeExpired();
        if (tokenId == null || expiresAt == null || !expiresAt.isAfter(clock.instant())) {
            return;
        }
        deniedUntil.put(tokenId, expiresAt);
    }

    public boolean isDenied(String tokenId) {
        if (tokenId == null) {
            return false;
        }
        Instant until = deniedUntil.get(tokenId);
        if (until == null) {
            return false;
        }
        if (!until.isAfter(clock.instant())) {
            deniedUntil.remove(tokenId, until);
            return false;
        }
        return true;
    }

    int size() {
        return deniedUntil.size();
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        deniedUntil.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }
}
