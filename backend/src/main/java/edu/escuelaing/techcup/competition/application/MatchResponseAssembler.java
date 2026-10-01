package edu.escuelaing.techcup.competition.application;

import edu.escuelaing.techcup.competition.api.dto.MatchResponse;
import edu.escuelaing.techcup.competition.domain.Match;
import edu.escuelaing.techcup.competition.domain.MatchEvent;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.tournaments.domain.Venue;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Builds the denormalised {@link MatchResponse} the frontend expects. Must be called inside the
 * caller's transaction: it walks the lazy associations of {@link Match} (teams, venue, referee,
 * event players), which Hibernate loads in batches. The permission flags depend on the match and
 * its tournament only.
 */
@Component
public class MatchResponseAssembler {

    public List<MatchResponse> toResponses(List<Match> found) {
        return found.stream().map(this::toResponse).toList();
    }

    public MatchResponse toResponse(Match match) {
        return new MatchResponse(
                match.getId(),
                match.getTournament().getId(),
                match.getPhase(),
                match.getRoundNumber(),
                teamRef(match.getHomeTeam()),
                teamRef(match.getAwayTeam()),
                venueRef(match.getVenue()),
                refereeRef(match.getReferee()),
                match.getScheduledAt(),
                match.getStatus(),
                match.getHomeScore(),
                match.getAwayScore(),
                match.getHomePenalties(),
                match.getAwayPenalties(),
                match.getCancelReason(),
                match.getWalkoverWinnerTeam() == null ? null : match.getWalkoverWinnerTeam().getId(),
                match.isResultEditable(),
                match.areTeamsEditable(),
                match.isReopenable(),
                match.getEvents().stream().map(MatchResponseAssembler::event).toList());
    }

    private static MatchResponse.TeamRef teamRef(Team team) {
        return new MatchResponse.TeamRef(team.getId(), team.getName(), team.getColors());
    }

    private static MatchResponse.VenueRef venueRef(Venue venue) {
        return venue == null ? null : new MatchResponse.VenueRef(venue.getId(), venue.getName());
    }

    private static MatchResponse.RefereeRef refereeRef(AppUser referee) {
        return referee == null ? null : new MatchResponse.RefereeRef(referee.getId(), referee.getFullName());
    }

    private static MatchResponse.Event event(MatchEvent event) {
        return new MatchResponse.Event(event.getId(), event.getTeam().getId(), event.getPlayer().getId(),
                event.getPlayer().getFullName(), event.getType(), event.getMinute());
    }
}
