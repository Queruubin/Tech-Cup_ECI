package edu.escuelaing.techcup.identity.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.escuelaing.techcup.shared.exception.LoginRateLimitException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Five failures per e-mail or per address in fifteen minutes; success or time clears them. */
class LoginAttemptServiceTest {

    private static final Instant START = Instant.parse("2026-03-10T10:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final LoginAttemptService service = new LoginAttemptService(clock);

    @Test
    void fourFailuresDoNotBlock() {
        fail("ana@escuelaing.edu.co", "10.0.0.1", 4);

        assertThatCode(() -> service.assertAllowed("ana@escuelaing.edu.co", "10.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    void fiveFailuresBlockTheEmailFromAnyAddress() {
        fail("ana@escuelaing.edu.co", "10.0.0.1", 5);

        assertThatThrownBy(() -> service.assertAllowed("Ana@Escuelaing.edu.co", "10.0.0.99"))
                .isInstanceOf(LoginRateLimitException.class)
                .hasMessageContaining("Demasiados intentos");
    }

    @Test
    void fiveFailuresBlockTheAddressForAnyEmail() {
        for (int attempt = 0; attempt < 5; attempt++) {
            service.recordFailure("user" + attempt + "@escuelaing.edu.co", "10.0.0.1");
        }

        assertThatThrownBy(() -> service.assertAllowed("someone-else@escuelaing.edu.co", "10.0.0.1"))
                .isInstanceOf(LoginRateLimitException.class);
        assertThatCode(() -> service.assertAllowed("someone-else@escuelaing.edu.co", "10.0.0.2"))
                .doesNotThrowAnyException();
    }

    @Test
    void aSuccessfulLoginClearsTheCounters() {
        fail("ana@escuelaing.edu.co", "10.0.0.1", 5);

        service.reset("ana@escuelaing.edu.co", "10.0.0.1");

        assertThatCode(() -> service.assertAllowed("ana@escuelaing.edu.co", "10.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    void theBlockExpiresWithTheWindow() {
        fail("ana@escuelaing.edu.co", "10.0.0.1", 5);
        clock.advance(LoginAttemptService.WINDOW.plusSeconds(1));

        assertThatCode(() -> service.assertAllowed("ana@escuelaing.edu.co", "10.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    void onlyFailuresInsideTheWindowCount() {
        fail("ana@escuelaing.edu.co", "10.0.0.1", 3);
        clock.advance(LoginAttemptService.WINDOW.plusSeconds(1));
        fail("ana@escuelaing.edu.co", "10.0.0.1", 2);

        assertThatCode(() -> service.assertAllowed("ana@escuelaing.edu.co", "10.0.0.1")).doesNotThrowAnyException();
    }

    private void fail(String email, String ip, int times) {
        for (int attempt = 0; attempt < times; attempt++) {
            service.recordFailure(email, ip);
        }
    }

    /** A clock the test can move forward. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
