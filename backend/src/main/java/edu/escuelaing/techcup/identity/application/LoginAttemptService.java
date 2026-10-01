package edu.escuelaing.techcup.identity.application;

import edu.escuelaing.techcup.shared.exception.LoginRateLimitException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Brute-force protection for the login endpoint: after {@value #MAX_ATTEMPTS} failed attempts for
 * the same e-mail <em>or</em> the same client address within {@link #WINDOW}, further attempts are
 * refused until the oldest failure leaves the window. A successful login clears both counters.
 *
 * <p>In-memory on purpose (single instance, ~100 users); a restart only forgets a few minutes of
 * failures. Memory stays bounded even against random e-mails or addresses: keys whose failures
 * all left the window are purged every {@link #WINDOW} by a scheduled task, and also right away
 * (at most once per {@link #PURGE_INTERVAL}) when a failure is recorded while more than
 * {@value #DEFAULT_PURGE_THRESHOLD} keys are tracked.
 */
@Component
public class LoginAttemptService {

    static final int MAX_ATTEMPTS = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final String BLOCKED_MESSAGE = "Demasiados intentos fallidos. Espere 15 minutos e intente de nuevo.";
    static final int DEFAULT_PURGE_THRESHOLD = 10_000;
    /** Minimum time between two purges triggered by {@link #recordFailure}, so a flood is not O(n) per request. */
    static final Duration PURGE_INTERVAL = Duration.ofMinutes(1);

    private static final String EMAIL_PREFIX = "email:";
    private static final String IP_PREFIX = "ip:";

    private final ConcurrentMap<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int purgeThreshold;
    private volatile Instant lastPurge = Instant.MIN;

    @Autowired
    public LoginAttemptService(Clock clock) {
        this(clock, DEFAULT_PURGE_THRESHOLD);
    }

    LoginAttemptService(Clock clock, int purgeThreshold) {
        this.clock = clock;
        this.purgeThreshold = purgeThreshold;
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
        if (failures.size() > purgeThreshold && !now.isBefore(lastPurge.plus(PURGE_INTERVAL))) {
            purgeExpired();
        }
    }

    public void reset(String email, String clientIp) {
        failures.remove(emailKey(email));
        failures.remove(ipKey(clientIp));
    }

    /** Drops every key whose failures have all left the window. */
    @Scheduled(fixedRate = 15, initialDelay = 15, timeUnit = TimeUnit.MINUTES)
    public void purgeExpired() {
        lastPurge = clock.instant();
        for (String key : failures.keySet()) {
            // computeIfPresent is atomic per key, so a failure recorded concurrently is never lost.
            failures.computeIfPresent(key, (ignored, recent) -> {
                synchronized (recent) {
                    prune(recent);
                    return recent.isEmpty() ? null : recent;
                }
            });
        }
    }

    /** Number of e-mails and addresses currently tracked. */
    int trackedKeys() {
        return failures.size();
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
        failures.compute(key, (ignored, existing) -> {
            Deque<Instant> recent = existing != null ? existing : new ArrayDeque<>();
            synchronized (recent) {
                prune(recent);
                recent.addLast(now);
            }
            return recent;
        });
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
