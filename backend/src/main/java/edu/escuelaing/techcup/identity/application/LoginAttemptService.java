package edu.escuelaing.techcup.identity.application;

import edu.escuelaing.techcup.shared.exception.LoginRateLimitException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Brute-force protection for the login endpoint: after {@value #MAX_ATTEMPTS} failed attempts for
 * the same e-mail <em>or</em> the same client address within {@link #WINDOW}, further attempts are
 * refused until the oldest failure leaves the window. A successful login clears both counters.
 *
 * <p>In-memory on purpose (single instance, ~100 users); a restart only forgets a few minutes of
 * failures.
 */
@Component
public class LoginAttemptService {

    static final int MAX_ATTEMPTS = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final String BLOCKED_MESSAGE = "Demasiados intentos fallidos. Espere 15 minutos e intente de nuevo.";

    private static final String EMAIL_PREFIX = "email:";
    private static final String IP_PREFIX = "ip:";

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginAttemptService(Clock clock) {
        this.clock = clock;
    }

    /** @throws LoginRateLimitException when either the e-mail or the address is currently blocked */
    public void assertAllowed(String email, String clientIp) {
        if (isBlocked(emailKey(email)) || isBlocked(ipKey(clientIp))) {
            throw new LoginRateLimitException(BLOCKED_MESSAGE);
        }
    }

    public void recordFailure(String email, String clientIp) {
        Instant now = clock.instant();
        record(emailKey(email), now);
        record(ipKey(clientIp), now);
    }

    public void reset(String email, String clientIp) {
        failures.remove(emailKey(email));
        failures.remove(ipKey(clientIp));
    }

    boolean isBlocked(String key) {
        if (key == null) {
            return false;
        }
        Deque<Instant> recent = failures.get(key);
        if (recent == null) {
            return false;
        }
        synchronized (recent) {
            prune(recent);
            if (recent.isEmpty()) {
                failures.remove(key, recent);
                return false;
            }
            return recent.size() >= MAX_ATTEMPTS;
        }
    }

    private void record(String key, Instant now) {
        if (key == null) {
            return;
        }
        Deque<Instant> recent = failures.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (recent) {
            prune(recent);
            recent.addLast(now);
        }
    }

    private void prune(Deque<Instant> recent) {
        Instant threshold = clock.instant().minus(WINDOW);
        while (!recent.isEmpty() && !recent.peekFirst().isAfter(threshold)) {
            recent.pollFirst();
        }
    }

    static String emailKey(String email) {
        return email == null || email.isBlank() ? null : EMAIL_PREFIX + email.trim().toLowerCase(Locale.ROOT);
    }

    static String ipKey(String clientIp) {
        return clientIp == null || clientIp.isBlank() ? null : IP_PREFIX + clientIp.trim();
    }
}
