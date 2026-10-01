package edu.escuelaing.techcup.competition.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The match State machine: a scheduled match is played or cancelled, and either outcome can be reopened. */
class MatchStatusTest {

    @Test
    void scheduledMovesToPlayedOrCancelled() {
        assertThatCode(() -> MatchStatus.SCHEDULED.transitionTo(MatchStatus.PLAYED)).doesNotThrowAnyException();
        assertThatCode(() -> MatchStatus.SCHEDULED.transitionTo(MatchStatus.CANCELLED)).doesNotThrowAnyException();
    }

    @Test
    void rejectsEveryTransitionOutsideTheMachine() {
        List<MatchStatus> all = List.of(MatchStatus.values());
        for (MatchStatus from : all) {
            for (MatchStatus to : all) {
                if (from.canTransitionTo(to)) {
                    continue;
                }
                assertThatThrownBy(() -> from.transitionTo(to))
                        .as("%s -> %s must be rejected", from, to)
                        .isInstanceOf(BusinessRuleException.class);
            }
        }
    }

    @Test
    void aPlayedOrCancelledMatchCanOnlyBeReopened() {
        assertThat(MatchStatus.PLAYED.allowedTargets()).containsExactly(MatchStatus.SCHEDULED);
        assertThat(MatchStatus.CANCELLED.allowedTargets()).containsExactly(MatchStatus.SCHEDULED);
        assertThatThrownBy(() -> MatchStatus.PLAYED.transitionTo(MatchStatus.CANCELLED))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> MatchStatus.CANCELLED.transitionTo(MatchStatus.PLAYED))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(MatchStatus.PLAYED.isFinished()).isTrue();
        assertThat(MatchStatus.CANCELLED.isFinished()).isTrue();
        assertThat(MatchStatus.SCHEDULED.isFinished()).isFalse();
    }
}
