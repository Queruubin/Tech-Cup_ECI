package edu.escuelaing.techcup.tournaments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The tournament State machine: every legal edge and every illegal one. */
class TournamentStatusTest {

    @ParameterizedTest
    @CsvSource({"DRAFT,ACTIVE", "ACTIVE,IN_PROGRESS", "IN_PROGRESS,FINISHED"})
    void allowsTheThreeLifecycleEdges(TournamentStatus from, TournamentStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
        assertThatCode(() -> from.transitionTo(to)).doesNotThrowAnyException();
    }

    @Test
    void rejectsEveryOtherTransition() {
        List<TournamentStatus> all = List.of(TournamentStatus.values());
        for (TournamentStatus from : all) {
            for (TournamentStatus to : all) {
                if (from.canTransitionTo(to)) {
                    continue;
                }
                assertThatThrownBy(() -> from.transitionTo(to))
                        .as("%s -> %s must be rejected", from, to)
                        .isInstanceOf(BusinessRuleException.class)
                        .hasMessageContaining(from.label())
                        .hasMessageContaining(to.label());
            }
        }
    }

    @Test
    void noStatusMayTransitionToItself() {
        for (TournamentStatus status : TournamentStatus.values()) {
            assertThat(status.canTransitionTo(status)).as("%s -> itself", status).isFalse();
        }
    }

    @ParameterizedTest
    @CsvSource({"DRAFT", "ACTIVE", "IN_PROGRESS"})
    void everyNonFinishedStatusMayBeSuspendedAndResumedInto(TournamentStatus status) {
        assertThat(status.canTransitionTo(TournamentStatus.SUSPENDED)).isTrue();
        assertThat(TournamentStatus.SUSPENDED.canTransitionTo(status)).isTrue();
    }

    @Test
    void aFinishedTournamentCannotBeSuspended() {
        assertThat(TournamentStatus.FINISHED.canTransitionTo(TournamentStatus.SUSPENDED)).isFalse();
        assertThat(TournamentStatus.SUSPENDED.canTransitionTo(TournamentStatus.FINISHED)).isFalse();
    }

    @Test
    void suspendedIsNeitherLiveNorEditable() {
        assertThat(TournamentStatus.SUSPENDED.isSuspended()).isTrue();
        assertThat(TournamentStatus.SUSPENDED.isLive()).isFalse();
        assertThat(TournamentStatus.SUSPENDED.isEditable()).isFalse();
    }

    @Test
    void finishedIsTerminal() {
        assertThat(TournamentStatus.FINISHED.allowedTargets()).isEmpty();
        assertThat(TournamentStatus.FINISHED.isFinished()).isTrue();
    }

    @Test
    void onlyDraftIsEditableAndOnlyActiveOrInProgressAreLive() {
        assertThat(TournamentStatus.DRAFT.isEditable()).isTrue();
        assertThat(TournamentStatus.ACTIVE.isEditable()).isFalse();
        assertThat(TournamentStatus.IN_PROGRESS.isEditable()).isFalse();
        assertThat(TournamentStatus.FINISHED.isEditable()).isFalse();

        assertThat(TournamentStatus.ACTIVE.isLive()).isTrue();
        assertThat(TournamentStatus.IN_PROGRESS.isLive()).isTrue();
        assertThat(TournamentStatus.DRAFT.isLive()).isFalse();
        assertThat(TournamentStatus.FINISHED.isLive()).isFalse();
    }
}
