package edu.escuelaing.techcup.competition.application;

import edu.escuelaing.techcup.competition.api.dto.StandingRow;
import edu.escuelaing.techcup.competition.domain.Match;
import edu.escuelaing.techcup.competition.domain.MatchPhase;
import edu.escuelaing.techcup.competition.domain.MatchStatus;
import edu.escuelaing.techcup.competition.domain.Standings;
import edu.escuelaing.techcup.competition.infrastructure.MatchRepository;
import edu.escuelaing.techcup.teams.application.TeamService;
import edu.escuelaing.techcup.tournaments.application.TournamentService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The group-stage table. Nothing is persisted: the rows are recomputed from the PLAYED GROUP
 * matches every time they are asked for, so correcting a result immediately corrects the table.
 * The arithmetic and the ordering live in the pure domain rule {@link Standings}.
 *
 * <p>The knockout matches, however, are drawn <b>once</b>, from the table as it stands when the
 * organizer advances out of the group stage ({@code MatchService.advance}). Correcting a group
 * result afterwards is allowed (the organizer keeps full correction power) but does <b>not</b>
 * reseed the existing knockout matches: if the qualifiers or their order change, the knockout
 * phases must be redone (undo phase, then advance again).
 */
@Service
public class StandingsService {

    private final MatchRepository matches;
    private final TournamentService tournamentService;
    private final TeamService teamService;

    public StandingsService(MatchRepository matches, TournamentService tournamentService, TeamService teamService) {
        this.matches = matches;
        this.tournamentService = tournamentService;
        this.teamService = teamService;
    }

    @Transactional(readOnly = true)
    public List<StandingRow> standings(Long tournamentId) {
        return rows(tournamentId).stream().map(StandingRow::from).toList();
    }

    /** Domain rows, used internally to seed the knockout phases. */
    @Transactional(readOnly = true)
    public List<Standings.Row> rows(Long tournamentId) {
        tournamentService.requireTournament(tournamentId);

        List<Standings.Competitor> competitors = tournamentService.approvedTeamIds(tournamentId).stream()
                .map(teamId -> new Standings.Competitor(teamId, teamService.requireTeam(teamId).getName()))
                .toList();

        List<Standings.Result> results = matches
                .findByTournamentIdAndStatusOrderByScheduledAtAscIdAsc(tournamentId, MatchStatus.PLAYED).stream()
                .filter(match -> match.getPhase() == MatchPhase.GROUP)
                .filter(match -> match.getHomeScore() != null && match.getAwayScore() != null)
                .map(StandingsService::toResult)
                .toList();

        return Standings.compute(competitors, results);
    }

    private static Standings.Result toResult(Match match) {
        return new Standings.Result(match.getHomeTeam().getId(), match.getAwayTeam().getId(),
                match.getHomeScore(), match.getAwayScore());
    }
}
