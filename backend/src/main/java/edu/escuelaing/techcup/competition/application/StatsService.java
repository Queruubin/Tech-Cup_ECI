package edu.escuelaing.techcup.competition.application;

import edu.escuelaing.techcup.competition.api.dto.SanctionedPlayerRow;
import edu.escuelaing.techcup.competition.api.dto.TopScorerRow;
import edu.escuelaing.techcup.competition.domain.EventType;
import edu.escuelaing.techcup.competition.domain.Match;
import edu.escuelaing.techcup.competition.domain.MatchEvent;
import edu.escuelaing.techcup.competition.infrastructure.MatchEventRepository;
import edu.escuelaing.techcup.competition.infrastructure.MatchRepository;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.tournaments.application.TournamentService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only statistics of a tournament: the top scorers and the players a referee must keep off
 * the pitch. The suspension arithmetic itself lives in the pure {@link SanctionRule}; this
 * service only gathers the facts it needs from the match history.
 */
@Service
public class StatsService {

    /** Matches are chronological; a null kick-off time sorts last, ties break on the id. */
    private static final Comparator<Match> PLAYING_ORDER = Comparator
            .comparing((Match match) -> match.getScheduledAt(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Match::getId);

    private final MatchRepository matches;
    private final MatchEventRepository events;
    private final MatchService matchService;
    private final TournamentService tournamentService;

    public StatsService(MatchRepository matches, MatchEventRepository events, MatchService matchService,
                        TournamentService tournamentService) {
        this.matches = matches;
        this.events = events;
        this.matchService = matchService;
        this.tournamentService = tournamentService;
    }

    @Transactional(readOnly = true)
    public List<TopScorerRow> topScorers(Long tournamentId) {
        tournamentService.requireTournament(tournamentId);
        return events.findTopScorers(tournamentId).stream()
                .map(row -> new TopScorerRow((Long) row[0], (String) row[1], (Long) row[2], (String) row[3],
                        ((Number) row[4]).longValue()))
                .toList();
    }

    /**
     * Players of either team who may not play {@code matchId} because of what happened in their
     * team's previous played match.
     */
    @Transactional(readOnly = true)
    public List<SanctionedPlayerRow> sanctionedPlayers(Long matchId) {
        Match match = matchService.requireMatch(matchId);
        Long tournamentId = match.getTournament().getId();

        List<SanctionedPlayerRow> rows = new ArrayList<>();
        for (Team team : List.of(match.getHomeTeam(), match.getAwayTeam())) {
            previousPlayedMatch(tournamentId, team.getId(), match)
                    .ifPresent(previous -> SanctionRule.apply(factsFrom(tournamentId, team, previous)).stream()
                            .map(sanction -> new SanctionedPlayerRow(sanction.userId(), sanction.fullName(),
                                    team.getId(), team.getName(), sanction.reason()))
                            .forEach(rows::add));
        }
        return List.copyOf(rows);
    }

    /** The most recent PLAYED match of the team that comes before {@code reference}. */
    private Optional<Match> previousPlayedMatch(Long tournamentId, Long teamId, Match reference) {
        return matches.findPlayedOfTeam(tournamentId, teamId).stream()
                .filter(candidate -> !candidate.getId().equals(reference.getId()))
                .filter(candidate -> PLAYING_ORDER.compare(candidate, reference) < 0)
                .max(PLAYING_ORDER);
    }

    /** Cards collected by the team's players in {@code previous}, plus their running yellow totals. */
    private List<SanctionRule.PlayerFacts> factsFrom(Long tournamentId, Team team, Match previous) {
        Map<Long, String> names = new LinkedHashMap<>();
        Set<Long> sentOff = new LinkedHashSet<>();
        Map<Long, Integer> booked = new HashMap<>();

        for (MatchEvent event : previous.getEvents()) {
            if (!event.getTeam().getId().equals(team.getId())) {
                continue;
            }
            Long playerId = event.getPlayer().getId();
            if (event.getType() == EventType.RED_CARD) {
                names.put(playerId, event.getPlayer().getFullName());
                sentOff.add(playerId);
            } else if (event.getType() == EventType.YELLOW_CARD) {
                names.put(playerId, event.getPlayer().getFullName());
                booked.merge(playerId, 1, Integer::sum);
            }
        }

        return names.entrySet().stream()
                .map(entry -> new SanctionRule.PlayerFacts(
                        entry.getKey(),
                        entry.getValue(),
                        sentOff.contains(entry.getKey()),
                        booked.getOrDefault(entry.getKey(), 0),
                        yellowsUpTo(tournamentId, entry.getKey(), previous)))
                .toList();
    }

    /** Yellow cards of a player in the tournament, counted up to and including {@code lastMatch}. */
    private int yellowsUpTo(Long tournamentId, Long playerId, Match lastMatch) {
        Instant boundary = lastMatch.getScheduledAt();
        return (int) events.findPlayerEvents(tournamentId, playerId, EventType.YELLOW_CARD).stream()
                .filter(event -> withinBoundary(event.getMatch(), lastMatch, boundary))
                .count();
    }

    private static boolean withinBoundary(Match candidate, Match lastMatch, Instant boundary) {
        if (candidate.getId().equals(lastMatch.getId())) {
            return true;
        }
        if (boundary == null || candidate.getScheduledAt() == null) {
            return candidate.getId() < lastMatch.getId();
        }
        return PLAYING_ORDER.compare(candidate, lastMatch) <= 0;
    }
}
