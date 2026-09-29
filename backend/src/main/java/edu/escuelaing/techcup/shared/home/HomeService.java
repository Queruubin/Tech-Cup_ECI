package edu.escuelaing.techcup.shared.home;

import edu.escuelaing.techcup.competition.api.dto.MatchResponse;
import edu.escuelaing.techcup.competition.api.dto.StandingRow;
import edu.escuelaing.techcup.competition.application.MatchService;
import edu.escuelaing.techcup.competition.application.StandingsService;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.teams.api.dto.TeamResponse;
import edu.escuelaing.techcup.teams.application.TeamService;
import edu.escuelaing.techcup.tournaments.api.dto.RegistrationResponse;
import edu.escuelaing.techcup.tournaments.api.dto.TournamentResponse;
import edu.escuelaing.techcup.tournaments.application.RegistrationService;
import edu.escuelaing.techcup.tournaments.application.TournamentService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Composes the home page from the domain modules — the "orchestrator" of the specification,
 * folded into the monolith. It calls the application services of the other modules only (never
 * their repositories), so every business rule they enforce still applies here.
 *
 * <p>What it returns: the current tournament (latest ACTIVE or IN_PROGRESS), the caller's team,
 * that team's registration for the current tournament (its receipt reference only for the team
 * captain), the next {@value #UPCOMING_MATCHES} scheduled matches and the top
 * {@value #STANDINGS_ROWS} rows of the table.</p>
 */
@Service
public class HomeService {

    static final int UPCOMING_MATCHES = 5;
    static final int STANDINGS_ROWS = 5;

    private final TournamentService tournamentService;
    private final RegistrationService registrationService;
    private final TeamService teamService;
    private final MatchService matchService;
    private final StandingsService standingsService;

    public HomeService(TournamentService tournamentService, RegistrationService registrationService,
                       TeamService teamService, MatchService matchService, StandingsService standingsService) {
        this.tournamentService = tournamentService;
        this.registrationService = registrationService;
        this.teamService = teamService;
        this.matchService = matchService;
        this.standingsService = standingsService;
    }

    @Transactional(readOnly = true)
    public HomeResponse home(AuthenticatedUser actor) {
        TeamResponse myTeam = teamService.findMine(actor.id()).orElse(null);
        TournamentResponse tournament = tournamentService.findCurrent().orElse(null);
        if (tournament == null) {
            return new HomeResponse(null, myTeam, null, List.of(), List.of());
        }

        RegistrationResponse myRegistration = myTeam == null
                ? null
                : registrationService.findByTeam(tournament.id(), myTeam.id())
                        .map(registration -> isCaptain(actor, myTeam) ? registration : registration.withoutReceipt())
                        .orElse(null);
        List<MatchResponse> upcomingMatches = matchService.upcoming(tournament.id(), UPCOMING_MATCHES);
        List<StandingRow> standingsTop = standingsService.standings(tournament.id()).stream()
                .limit(STANDINGS_ROWS)
                .toList();

        return new HomeResponse(tournament, myTeam, myRegistration, upcomingMatches, standingsTop);
    }

    /** Only the captain uploaded the payment receipt, so only the captain gets its reference back. */
    private static boolean isCaptain(AuthenticatedUser actor, TeamResponse team) {
        return team.captain() != null && actor.id().equals(team.captain().id());
    }
}
