package edu.escuelaing.techcup.competition.domain;

import edu.escuelaing.techcup.teams.domain.Team;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure domain rules behind the history of a finished tournament (nothing is persisted: they are
 * recomputed from the matches on every read).
 *
 * <ul>
 *   <li><b>Podium</b>. When the FINAL decided a champion (PLAYED, penalties included, or CANCELLED
 *       with a walkover winner): 1 = its winner, 2 = the other finalist, 3 = the better of the two
 *       semi-final losers by their numbers over <em>every</em> match they played in the
 *       tournament (points, then goal difference, then goals for, then name; no third without
 *       semi-finals). Otherwise (e.g. finished by date during the group stage) the top three of
 *       the general table with at least one match played. Only what exists is returned.</li>
 *   <li><b>Best defence</b> ("valla menos vencida"): the fewest goals conceded per match played;
 *       ties go to more matches played, then fewer goals conceded in total, then the name.</li>
 *   <li><b>Top scorers</b>: every player tied on the highest goal count.</li>
 * </ul>
 * The numbers count every PLAYED match with a score, any phase; a level knockout match decided on
 * penalties counts as a draw (the shoot-out only decides who goes through). Cancelled matches
 * (walkovers included) count for nothing.
 */
public final class TournamentRecords {

    /** Where the podium comes from. */
    public enum PodiumSource {
        /** The final and the semi-finals. */
        KNOCKOUT,
        /** The general table (no final decided). */
        STANDINGS
    }

    /** A team's numbers over the matches it played in the tournament. */
    public record TeamTally(Long teamId, String teamName, int played, int won, int drawn, int lost,
                            int goalsFor, int goalsAgainst) {

        public int points() {
            return won * Standings.POINTS_WIN + drawn * Standings.POINTS_DRAW;
        }

        public int goalDifference() {
            return goalsFor - goalsAgainst;
        }

        static TeamTally empty(Team team) {
            return new TeamTally(team.getId(), team.getName(), 0, 0, 0, 0, 0, 0);
        }

        TeamTally plus(int scored, int conceded) {
            return new TeamTally(teamId, teamName, played + 1,
                    won + (scored > conceded ? 1 : 0), drawn + (scored == conceded ? 1 : 0),
                    lost + (scored < conceded ? 1 : 0), goalsFor + scored, goalsAgainst + conceded);
        }
    }

    /** One step of the podium with the numbers it was decided on. */
    public record PodiumPlace(int position, Long teamId, String teamName, int played, int points,
                              int goalDifference, int goalsFor, int goalsAgainst) {

        static PodiumPlace of(int position, TeamTally tally) {
            return new PodiumPlace(position, tally.teamId(), tally.teamName(), tally.played(), tally.points(),
                    tally.goalDifference(), tally.goalsFor(), tally.goalsAgainst());
        }

        static PodiumPlace of(int position, Standings.Row row) {
            return new PodiumPlace(position, row.teamId(), row.teamName(), row.played(), row.points(),
                    row.goalDifference(), row.goalsFor(), row.goalsAgainst());
        }
    }

    /**
     * @param source               null when there is no podium at all
     * @param places               positions 1..3 that exist, in order
     * @param thirdPlaceCandidates the semi-final losers compared for the third place, best first
     *                             (empty outside {@link PodiumSource#KNOCKOUT})
     */
    public record Podium(PodiumSource source, List<PodiumPlace> places, List<TeamTally> thirdPlaceCandidates) {

        public Optional<PodiumPlace> place(int position) {
            return places.stream().filter(place -> place.position() == position).findFirst();
        }
    }

    public record Defence(Long teamId, String teamName, int goalsConceded, int matchesPlayed) {
    }

    /** One scorer of the tournament (a player who scored for two teams appears once, with the first). */
    public record Scorer(Long playerId, String playerName, Long teamId, String teamName, long goals) {
    }

    /** Points, goal difference, goals for (higher first), then name: the third-place tie-break. */
    public static final Comparator<TeamTally> RANKING = Comparator
            .comparingInt(TeamTally::points).reversed()
            .thenComparing(Comparator.comparingInt(TeamTally::goalDifference).reversed())
            .thenComparing(Comparator.comparingInt(TeamTally::goalsFor).reversed())
            .thenComparing(TeamTally::teamName, String.CASE_INSENSITIVE_ORDER);

    /** Fewest goals conceded per match (exact, by cross-multiplication), more matches, fewer goals, name. */
    public static final Comparator<TeamTally> DEFENCE = ((Comparator<TeamTally>) (first, second) ->
            Long.compare((long) first.goalsAgainst() * second.played(), (long) second.goalsAgainst() * first.played()))
            .thenComparing(Comparator.comparingInt(TeamTally::played).reversed())
            .thenComparingInt(TeamTally::goalsAgainst)
            .thenComparing(TeamTally::teamName, String.CASE_INSENSITIVE_ORDER);

    private TournamentRecords() {
    }

    /** Every team that played at least one match, with its numbers over all its PLAYED matches. */
    public static Map<Long, TeamTally> tallies(List<Match> matches) {
        Map<Long, TeamTally> tallies = new LinkedHashMap<>();
        for (Match match : matches) {
            if (!match.isPlayed() || match.getHomeScore() == null || match.getAwayScore() == null) {
                continue;
            }
            int home = match.getHomeScore();
            int away = match.getAwayScore();
            tallies.put(match.getHomeTeam().getId(), tallies
                    .getOrDefault(match.getHomeTeam().getId(), TeamTally.empty(match.getHomeTeam())).plus(home, away));
            tallies.put(match.getAwayTeam().getId(), tallies
                    .getOrDefault(match.getAwayTeam().getId(), TeamTally.empty(match.getAwayTeam())).plus(away, home));
        }
        return tallies;
    }

    /**
     * @param matches every match of the tournament, any phase and status
     * @param general the general table (standings order), used only when no final decided a champion
     */
    public static Podium podium(List<Match> matches, List<Standings.Row> general) {
        Map<Long, TeamTally> tallies = tallies(matches);
        Optional<Match> decidedFinal = matches.stream()
                .filter(match -> match.getPhase() == MatchPhase.FINAL)
                .filter(match -> decidedWinner(match).isPresent())
                .max(Comparator.comparing(Match::getId, Comparator.nullsFirst(Comparator.naturalOrder())));
        if (decidedFinal.isPresent()) {
            Match finalMatch = decidedFinal.get();
            Team champion = decidedWinner(finalMatch).orElseThrow();
            Team runnerUp = finalMatch.opponentOf(champion.getId());
            List<PodiumPlace> places = new ArrayList<>(3);
            places.add(PodiumPlace.of(1, tallyOf(tallies, champion)));
            places.add(PodiumPlace.of(2, tallyOf(tallies, runnerUp)));
            List<TeamTally> candidates = matches.stream()
                    .filter(match -> match.getPhase() == MatchPhase.SEMIFINAL)
                    .filter(match -> decidedWinner(match).isPresent())
                    .map(match -> match.opponentOf(decidedWinner(match).orElseThrow().getId()))
                    .filter(loser -> !loser.getId().equals(champion.getId()) && !loser.getId().equals(runnerUp.getId()))
                    .map(loser -> tallyOf(tallies, loser))
                    .sorted(RANKING)
                    .toList();
            if (!candidates.isEmpty()) {
                places.add(PodiumPlace.of(3, candidates.get(0)));
            }
            return new Podium(PodiumSource.KNOCKOUT, List.copyOf(places), candidates);
        }
        List<Standings.Row> top = general.stream()
                .filter(row -> row.played() > 0)
                .limit(3)
                .toList();
        List<PodiumPlace> places = new ArrayList<>(top.size());
        for (int index = 0; index < top.size(); index++) {
            places.add(PodiumPlace.of(index + 1, top.get(index)));
        }
        return new Podium(places.isEmpty() ? null : PodiumSource.STANDINGS, List.copyOf(places), List.of());
    }

    /** The team with the best defence among those that played at least one match. */
    public static Optional<Defence> bestDefence(List<Match> matches) {
        return tallies(matches).values().stream()
                .filter(tally -> tally.played() > 0)
                .min(DEFENCE)
                .map(tally -> new Defence(tally.teamId(), tally.teamName(), tally.goalsAgainst(), tally.played()));
    }

    /**
     * Every player tied on the highest goal count (empty when nobody scored).
     *
     * @param rows one row per player and team, in any order
     */
    public static List<Scorer> topScorers(List<Scorer> rows) {
        Map<Long, Scorer> byPlayer = new LinkedHashMap<>();
        for (Scorer row : rows) {
            byPlayer.merge(row.playerId(), row, (first, second) -> new Scorer(first.playerId(), first.playerName(),
                    first.teamId(), first.teamName(), first.goals() + second.goals()));
        }
        long best = byPlayer.values().stream().mapToLong(Scorer::goals).max().orElse(0);
        if (best <= 0) {
            return List.of();
        }
        return byPlayer.values().stream()
                .filter(scorer -> scorer.goals() == best)
                .sorted(Comparator.comparing(Scorer::playerName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** The team that went through: the winner of a played match or the walkover winner of a cancelled one. */
    static Optional<Team> decidedWinner(Match match) {
        if (match.getStatus() == MatchStatus.CANCELLED) {
            return Optional.ofNullable(match.getWalkoverWinnerTeam());
        }
        return match.winner();
    }

    private static TeamTally tallyOf(Map<Long, TeamTally> tallies, Team team) {
        return tallies.getOrDefault(team.getId(), TeamTally.empty(team));
    }
}
