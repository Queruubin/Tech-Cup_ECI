package edu.escuelaing.techcup.competition.api.dto;

import edu.escuelaing.techcup.competition.domain.CancelReason;
import edu.escuelaing.techcup.competition.domain.EventType;
import edu.escuelaing.techcup.competition.domain.MatchPhase;
import edu.escuelaing.techcup.competition.domain.MatchStatus;
import java.time.Instant;
import java.util.List;

/**
 * A match as the frontend consumes it: teams, venue and referee are denormalised so a match card
 * can be rendered without extra requests. {@code venue} and {@code referee} are null until an
 * organizer assigns them. {@code walkoverWinnerTeamId} is set only on cancelled knockout matches.
 * The three flags describe what an organizer may do right now, all of them only while the
 * tournament is IN_PROGRESS: {@code resultEditable} (SCHEDULED or PLAYED) tells whether
 * {@code POST /matches/{id}/result} would be accepted, {@code teamsEditable} (not PLAYED) whether
 * {@code PATCH /matches/{id}} may replace the teams, and {@code reopenable} (PLAYED or CANCELLED)
 * whether {@code POST /matches/{id}/reopen} would be accepted.
 */
public record MatchResponse(
        Long id,
        Long tournamentId,
        MatchPhase phase,
        int roundNumber,
        TeamRef homeTeam,
        TeamRef awayTeam,
        VenueRef venue,
        RefereeRef referee,
        Instant scheduledAt,
        MatchStatus status,
        Integer homeScore,
        Integer awayScore,
        Integer homePenalties,
        Integer awayPenalties,
        CancelReason cancelReason,
        Long walkoverWinnerTeamId,
        boolean resultEditable,
        boolean teamsEditable,
        boolean reopenable,
        List<Event> events) {

    public record TeamRef(Long id, String name, String colors) {
    }

    public record VenueRef(Long id, String name) {
    }

    public record RefereeRef(Long id, String fullName) {
    }

    public record Event(Long id, Long teamId, Long playerId, String playerName, EventType type, Integer minute) {
    }
}
