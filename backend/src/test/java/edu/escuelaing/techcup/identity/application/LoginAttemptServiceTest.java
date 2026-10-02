package edu.escuelaing.techcup.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.escuelaing.techcup.shared.exception.LoginRateLimitException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Five failures per e-mail in fifteen minutes; success or time clears them. */
class LoginAttemptServiceTest {

    private static final Instant START = Instant.parse("2026-03-10T10:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final LoginAttemptService service = new LoginAttemptService(clock);

    @Test
    void fourFailuresDoNotBlock() {
        fail("ana@escuelaing.edu.co", 4);

        assertThatCode(() -> service.assertAllowed("ana@escuelaing.edu.co")).doesNotThrowAnyException();
    }

    @Test
    void fiveFailuresBlockTheEmailIgnoringCase() {
        fail("ana@escuelaing.edu.co", 5);

        assertThatThrownBy(() -> service.assertAllowed("Ana@Escuelaing.edu.co"))
                .isInstanceOf(LoginRateLimitException.class)
                .hasMessageContaining("Demasiados intentos");
    }

    /** Users behind one shared address must never lock each other out. */
    @Test
    void failuresForOtherEmailsNeverBlockAnAccount() {
        for (int attempt = 0; attempt < 10; attempt++) {
            service.recordFailure("user" + attempt + "@escuelaing.edu.co");
        }

        assertThatCode(() -> service.assertAllowed("someone-else@escuelaing.edu.co")).doesNotThrowAnyException();
    }

    @Test
    void aSuccessfulLoginClearsTheCounter() {
        fail("ana@escuelaing.edu.co", 5);

        service.reset("ana@escuelaing.edu.co");

        assertThatCode(() -> service.assertAllowed("ana@escuelaing.edu.co")).doesNotThrowAnyException();
    }

    @Test
    void theBlockExpiresWithTheWindow() {
        fail("ana@escuelaing.edu.co", 5);
        clock.advance(LoginAttemptService.WINDOW.plusSeconds(1));

        assertThatCode(() -> service.assertAllowed("ana@escuelaing.edu.co")).doesNotThrowAnyException();
    }

    @Test
    void onlyFailuresInsideTheWindowCount() {
        fail("ana@escuelaing.edu.co", 3);
        clock.advance(LoginAttemptService.WINDOW.plusSeconds(1));
        fail("ana@escuelaing.edu.co", 2);

        assertThatCode(() -> service.assertAllowed("ana@escuelaing.edu.co")).doesNotThrowAnyException();
    }

    // --- memory bound ------------------------------------------------------------------------

    @Test
    void theScheduledPurgeDropsOnlyKeysWhoseFailuresLeftTheWindow() {
        fail("old@escuelaing.edu.co", 2);
        clock.advance(LoginAttemptService.WINDOW.plusSeconds(1));
        fail("recent@escuelaing.edu.co", 5);

        service.purgeExpired();

        assertThat(service.trackedKeys()).isEqualTo(1);
        assertThatThrownBy(() -> service.assertAllowed("recent@escuelaing.edu.co"))
                .isInstanceOf(LoginRateLimitException.class);
    }

    @Test
    void randomKeysAreForgottenOnceExpiredWhenTheMapGrowsPastTheThreshold() {
        LoginAttemptService bounded = new LoginAttemptService(clock, 4);
        for (int attempt = 0; attempt < 5; attempt++) {
            bounded.recordFailure("random" + attempt + "@example.com");
        }
        assertThat(bounded.trackedKeys()).isEqualTo(5);

        clock.advance(LoginAttemptService.WINDOW.plusSeconds(1));
        bounded.recordFailure("ana@escuelaing.edu.co");

        assertThat(bounded.trackedKeys()).isEqualTo(1);
    }

    @Test
    void belowTheThresholdNothingIsPurgedOnARecordedFailure() {
        LoginAttemptService bounded = new LoginAttemptService(clock, 100);
        bounded.recordFailure("old@escuelaing.edu.co");
        clock.advance(LoginAttemptService.WINDOW.plusSeconds(1));

        bounded.recordFailure("ana@escuelaing.edu.co");

        assertThat(bounded.trackedKeys()).isEqualTo(2);
    }

    private void fail(String email, int times) {
        for (int attempt = 0; attempt < times; attempt++) {
            service.recordFailure(email);
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
