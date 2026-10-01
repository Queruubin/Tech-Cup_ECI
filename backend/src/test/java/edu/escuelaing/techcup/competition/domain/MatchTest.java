package edu.escuelaing.techcup.competition.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.tournaments.domain.Tournament;
import edu.escuelaing.techcup.tournaments.domain.TournamentStatus;
import org.junit.jupiter.api.Test;

/** What an organizer may still do with a match: record/correct, replace teams, reopen. */
class MatchTest {

    @Test
    void aScheduledOrPlayedMatchOfAnInProgressTournamentAcceptsAResult() {
        assertThat(match(TournamentStatus.IN_PROGRESS, MatchStatus.SCHEDULED).isResultEditable()).isTrue();
        assertThat(match(TournamentStatus.IN_PROGRESS, MatchStatus.PLAYED).isResultEditable()).isTrue();
    }

    @Test
    void aCancelledMatchDoesNotAcceptAResult() {
        assertThat(match(TournamentStatus.IN_PROGRESS, MatchStatus.CANCELLED).isResultEditable()).isFalse();
    }

    @Test
    void theTeamsCanChangeUntilAResultIsRecorded() {
        assertThat(match(TournamentStatus.IN_PROGRESS, MatchStatus.SCHEDULED).areTeamsEditable()).isTrue();
        assertThat(match(TournamentStatus.IN_PROGRESS, MatchStatus.CANCELLED).areTeamsEditable()).isTrue();
        assertThat(match(TournamentStatus.IN_PROGRESS, MatchStatus.PLAYED).areTeamsEditable()).isFalse();
    }

    @Test
    void onlyAPlayedOrCancelledMatchCanBeReopened() {
        assertThat(match(TournamentStatus.IN_PROGRESS, MatchStatus.PLAYED).isReopenable()).isTrue();
        assertThat(match(TournamentStatus.IN_PROGRESS, MatchStatus.CANCELLED).isReopenable()).isTrue();
        assertThat(match(TournamentStatus.IN_PROGRESS, MatchStatus.SCHEDULED).isReopenable()).isFalse();
    }

    @Test
    void nothingIsEditableOutsideAnInProgressTournament() {
        Match finished = match(TournamentStatus.FINISHED, MatchStatus.PLAYED);
        assertThat(finished.isResultEditable()).isFalse();
        assertThat(finished.areTeamsEditable()).isFalse();
        assertThat(finished.isReopenable()).isFalse();
        Match notStarted = match(TournamentStatus.ACTIVE, MatchStatus.SCHEDULED);
        assertThat(notStarted.isResultEditable()).isFalse();
        assertThat(notStarted.areTeamsEditable()).isFalse();
    }

    @Test
    void reopeningForgetsTheWholeOutcome() {
        Match match = match(TournamentStatus.IN_PROGRESS, MatchStatus.CANCELLED);
        match.setCancelReason(CancelReason.NO_SHOW);
        match.setWalkoverWinnerTeam(match.getHomeTeam());

        match.reopen();

        assertThat(match.getStatus()).isEqualTo(MatchStatus.SCHEDULED);
        assertThat(match.getCancelReason()).isNull();
        assertThat(match.getWalkoverWinnerTeam()).isNull();
    }

    @Test
    void aScheduledMatchCannotBeReopened() {
        Match match = match(TournamentStatus.IN_PROGRESS, MatchStatus.SCHEDULED);

        assertThatThrownBy(match::reopen).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void replacingATeamKeepsItsSide() {
        Match match = match(TournamentStatus.IN_PROGRESS, MatchStatus.SCHEDULED);
        Team newcomer = team(30L);

        match.replaceTeam(match.getAwayTeam(), newcomer);

        assertThat(match.getHomeTeam().getId()).isEqualTo(10L);
        assertThat(match.getAwayTeam()).isSameAs(newcomer);
        assertThatThrownBy(() -> match.replaceTeam(team(99L), team(31L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Match match(TournamentStatus tournamentStatus, MatchStatus status) {
        return Match.builder()
                .id(3L)
                .tournament(Tournament.builder().id(1L).status(tournamentStatus).build())
                .phase(MatchPhase.SEMIFINAL)
                .homeTeam(team(10L))
                .awayTeam(team(20L))
                .status(status)
                .build();
    }

    private static Team team(Long id) {
        return Team.builder().id(id).name("Team " + id).captain(AppUser.builder().id(id * 100).build()).build();
    }
}
