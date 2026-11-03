package edu.escuelaing.techcup.competition.domain;

import static org.assertj.core.api.Assertions.assertThat;

import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.teams.domain.TeamStatus;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Podium, best defence and top scorers of a finished tournament. */
class TournamentRecordsTest {

    private static final Team TIGERS = team(1L, "Tigers");
    private static final Team LIONS = team(2L, "Lions");
    private static final Team PUMAS = team(3L, "Pumas");
    private static final Team BEARS = team(4L, "Bears");
    private long nextId = 1;

    // --- podium ---------------------------------------------------------------------------------

    @Test
    void theFinalWonOnPenaltiesGivesChampionAndRunnerUp() {
        List<Match> matches = new ArrayList<>(semiFinals());
        Match finalMatch = played(MatchPhase.FINAL, TIGERS, LIONS, 1, 1);
        finalMatch.setHomePenalties(3);
        finalMatch.setAwayPenalties(4);
        matches.add(finalMatch);

        var podium = TournamentRecords.podium(matches, List.of());

        assertThat(podium.source()).isEqualTo(TournamentRecords.PodiumSource.KNOCKOUT);
        assertThat(podium.place(1)).get().extracting(TournamentRecords.PodiumPlace::teamId).isEqualTo(2L);
        assertThat(podium.place(2)).get().extracting(TournamentRecords.PodiumPlace::teamId).isEqualTo(1L);
    }

    @Test
    void aFinalDecidedByWalkoverStillCrownsTheTeamThatWentThrough() {
        List<Match> matches = new ArrayList<>(semiFinals());
        Match finalMatch = match(MatchPhase.FINAL, TIGERS, LIONS);
        finalMatch.setStatus(MatchStatus.CANCELLED);
        finalMatch.setCancelReason(CancelReason.NO_SHOW);
        finalMatch.setWalkoverWinnerTeam(TIGERS);
        matches.add(finalMatch);

        var podium = TournamentRecords.podium(matches, List.of());

        // Bears lost their semi-final 0-1, Pumas 0-2: Bears are third on goal difference.
        assertThat(podium.places()).extracting(TournamentRecords.PodiumPlace::teamName)
                .containsExactly("Tigers", "Lions", "Bears");
    }

    /**
     * Pumas and Bears lost the semi-finals. Over all their matches: Pumas 4 pts (W, D, L), Bears 4 pts
     * (W, D, L) too; goal difference decides: Pumas +1, Bears 0.
     */
    @Test
    void theThirdPlaceIsTheBetterSemiFinalLoserOverAllItsMatches() {
        List<Match> matches = new ArrayList<>();
        matches.add(played(MatchPhase.GROUP, PUMAS, team(9L, "Wolves"), 3, 0));
        matches.add(played(MatchPhase.GROUP, PUMAS, team(8L, "Hawks"), 1, 1));
        matches.add(played(MatchPhase.GROUP, BEARS, team(7L, "Owls"), 2, 1));
        matches.add(played(MatchPhase.GROUP, BEARS, team(6L, "Foxes"), 0, 0));
        matches.add(played(MatchPhase.SEMIFINAL, TIGERS, PUMAS, 2, 0));
        matches.add(played(MatchPhase.SEMIFINAL, LIONS, BEARS, 1, 0));
        matches.add(played(MatchPhase.FINAL, TIGERS, LIONS, 2, 1));

        var podium = TournamentRecords.podium(matches, List.of());

        assertThat(podium.place(3)).get().satisfies(third -> {
            assertThat(third.teamName()).isEqualTo("Pumas");
            assertThat(third.points()).isEqualTo(4);
            assertThat(third.goalDifference()).isEqualTo(1);
        });
        assertThat(podium.thirdPlaceCandidates()).extracting(TournamentRecords.TeamTally::teamName)
                .containsExactly("Pumas", "Bears");
        assertThat(podium.thirdPlaceCandidates().get(1).goalDifference()).isZero();
    }

    @Test
    void equalPointsAndGoalDifferenceGoToGoalsForThenToTheName() {
        List<Match> byGoals = new ArrayList<>();
        byGoals.add(played(MatchPhase.GROUP, PUMAS, team(9L, "Wolves"), 3, 2));
        byGoals.add(played(MatchPhase.GROUP, BEARS, team(7L, "Owls"), 1, 0));
        byGoals.add(played(MatchPhase.SEMIFINAL, TIGERS, PUMAS, 1, 0));
        byGoals.add(played(MatchPhase.SEMIFINAL, LIONS, BEARS, 1, 0));
        byGoals.add(played(MatchPhase.FINAL, TIGERS, LIONS, 1, 0));
        // Pumas 3 pts, GD 0, GF 3; Bears 3 pts, GD 0, GF 1.
        assertThat(TournamentRecords.podium(byGoals, List.of()).place(3)).get()
                .extracting(TournamentRecords.PodiumPlace::teamName).isEqualTo("Pumas");

        List<Match> byName = new ArrayList<>();
        byName.add(played(MatchPhase.SEMIFINAL, TIGERS, PUMAS, 1, 0));
        byName.add(played(MatchPhase.SEMIFINAL, LIONS, BEARS, 1, 0));
        byName.add(played(MatchPhase.FINAL, TIGERS, LIONS, 1, 0));
        // Identical numbers: Bears before Pumas alphabetically.
        assertThat(TournamentRecords.podium(byName, List.of()).place(3)).get()
                .extracting(TournamentRecords.PodiumPlace::teamName).isEqualTo("Bears");
    }

    @Test
    void withoutAFinalThePodiumIsTheTopThreeOfTheGeneralTable() {
        List<Match> groupOnly = List.of(played(MatchPhase.GROUP, TIGERS, LIONS, 1, 0));
        List<Standings.Row> general = List.of(
                row(1, LIONS, 2, 6), row(2, TIGERS, 2, 4), row(3, PUMAS, 1, 1), row(4, BEARS, 1, 0),
                row(5, team(9L, "Wolves"), 0, 0));

        var podium = TournamentRecords.podium(groupOnly, general);

        assertThat(podium.source()).isEqualTo(TournamentRecords.PodiumSource.STANDINGS);
        assertThat(podium.places()).extracting(TournamentRecords.PodiumPlace::teamName)
                .containsExactly("Lions", "Tigers", "Pumas");
        assertThat(podium.thirdPlaceCandidates()).isEmpty();
    }

    @Test
    void onlyWhatExistsIsShown() {
        // A final without semi-finals: champion and runner-up only.
        var finalOnly = TournamentRecords.podium(List.of(played(MatchPhase.FINAL, TIGERS, LIONS, 0, 2)), List.of());
        assertThat(finalOnly.places()).extracting(TournamentRecords.PodiumPlace::position).containsExactly(1, 2);

        // Only two teams ever played: two places; nobody played: no podium at all.
        var twoPlayed = TournamentRecords.podium(List.of(), List.of(row(1, LIONS, 1, 3), row(2, TIGERS, 1, 0),
                row(3, PUMAS, 0, 0)));
        assertThat(twoPlayed.places()).hasSize(2);
        var nothing = TournamentRecords.podium(List.of(), List.of(row(1, LIONS, 0, 0)));
        assertThat(nothing.source()).isNull();
        assertThat(nothing.places()).isEmpty();
    }

    // --- best defence ---------------------------------------------------------------------------

    @Test
    void theBestDefenceConcedesTheFewestGoalsPerMatch() {
        List<Match> matches = List.of(
                played(MatchPhase.GROUP, TIGERS, LIONS, 0, 1),   // Tigers concede 1 in 1
                played(MatchPhase.GROUP, PUMAS, BEARS, 2, 2),    // Pumas 2 in 1, Bears 2 in 1
                played(MatchPhase.GROUP, LIONS, PUMAS, 3, 1));   // Lions 1 in 2 -> 0.5 per match

        assertThat(TournamentRecords.bestDefence(matches)).get().satisfies(best -> {
            assertThat(best.teamName()).isEqualTo("Lions");
            assertThat(best.goalsConceded()).isEqualTo(1);
            assertThat(best.matchesPlayed()).isEqualTo(2);
        });
    }

    @Test
    void bestDefenceTiesGoToMoreMatchesThenFewerGoalsThenTheName() {
        // Tigers 1 in 2, Pumas 1 in 2 and Lions 2 in 4 (all 0.5 per match): Lions played more.
        List<Match> moreMatches = new ArrayList<>();
        moreMatches.add(played(MatchPhase.GROUP, TIGERS, PUMAS, 1, 1));
        moreMatches.add(played(MatchPhase.GROUP, TIGERS, BEARS, 2, 0));
        moreMatches.add(played(MatchPhase.GROUP, LIONS, PUMAS, 0, 1));
        moreMatches.add(played(MatchPhase.GROUP, LIONS, BEARS, 0, 1));
        moreMatches.add(played(MatchPhase.GROUP, LIONS, team(9L, "Wolves"), 1, 0));
        moreMatches.add(played(MatchPhase.GROUP, LIONS, team(8L, "Hawks"), 2, 0));
        assertThat(TournamentRecords.bestDefence(moreMatches)).get()
                .extracting(TournamentRecords.Defence::teamName).isEqualTo("Lions");

        // Both kept a clean sheet in their only match: the name decides.
        List<Match> byName = List.of(played(MatchPhase.GROUP, TIGERS, PUMAS, 1, 0),
                played(MatchPhase.GROUP, BEARS, LIONS, 2, 0));
        assertThat(TournamentRecords.bestDefence(byName)).get()
                .extracting(TournamentRecords.Defence::teamName).isEqualTo("Bears");
    }

    @Test
    void cancelledMatchesCountForNothing() {
        Match walkover = match(MatchPhase.SEMIFINAL, TIGERS, LIONS);
        walkover.setStatus(MatchStatus.CANCELLED);
        walkover.setWalkoverWinnerTeam(TIGERS);
        assertThat(TournamentRecords.bestDefence(List.of(walkover))).isEmpty();
        assertThat(TournamentRecords.tallies(List.of(walkover))).isEmpty();
    }

    // --- top scorers ----------------------------------------------------------------------------

    @Test
    void everyPlayerTiedOnTheMostGoalsIsATopScorer() {
        var scorers = TournamentRecords.topScorers(List.of(
                new TournamentRecords.Scorer(10L, "Zoe", 1L, "Tigers", 4),
                new TournamentRecords.Scorer(11L, "Ana", 2L, "Lions", 4),
                new TournamentRecords.Scorer(12L, "Luis", 3L, "Pumas", 3)));

        assertThat(scorers).extracting(TournamentRecords.Scorer::playerName).containsExactly("Ana", "Zoe");
        assertThat(scorers).allSatisfy(scorer -> assertThat(scorer.goals()).isEqualTo(4));
    }

    @Test
    void goalsOfOnePlayerForTwoTeamsAddUpAndNobodyScoringMeansNoTopScorer() {
        var scorers = TournamentRecords.topScorers(List.of(
                new TournamentRecords.Scorer(10L, "Zoe", 1L, "Tigers", 2),
                new TournamentRecords.Scorer(10L, "Zoe", 2L, "Lions", 2),
                new TournamentRecords.Scorer(11L, "Ana", 2L, "Lions", 3)));
        assertThat(scorers).singleElement().satisfies(top -> {
            assertThat(top.playerName()).isEqualTo("Zoe");
            assertThat(top.goals()).isEqualTo(4);
            assertThat(top.teamName()).isEqualTo("Tigers");
        });

        assertThat(TournamentRecords.topScorers(List.of())).isEmpty();
    }

    // --- helpers --------------------------------------------------------------------------------

    /** Tigers 2-0 Pumas and Lions 1-0 Bears. */
    private List<Match> semiFinals() {
        return List.of(played(MatchPhase.SEMIFINAL, TIGERS, PUMAS, 2, 0),
                played(MatchPhase.SEMIFINAL, LIONS, BEARS, 1, 0));
    }

    private Match played(MatchPhase phase, Team home, Team away, int homeScore, int awayScore) {
        Match match = match(phase, home, away);
        match.setStatus(MatchStatus.PLAYED);
        match.setHomeScore(homeScore);
        match.setAwayScore(awayScore);
        return match;
    }

    private Match match(MatchPhase phase, Team home, Team away) {
        return Match.builder().id(nextId++).phase(phase).roundNumber(1).homeTeam(home).awayTeam(away)
                .status(MatchStatus.SCHEDULED).build();
    }

    private static Standings.Row row(int position, Team team, int played, int points) {
        return new Standings.Row(position, team.getId(), team.getName(), played, points / 3, points % 3, 0,
                points, 0, points, points);
    }

    private static Team team(Long id, String name) {
        return Team.builder().id(id).name(name).colors("blue")
                .captain(AppUser.builder().id(id * 100).fullName("Captain " + id).build())
                .status(TeamStatus.ACTIVE).build();
    }
}
