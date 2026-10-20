package edu.escuelaing.techcup.competition.application;

import edu.escuelaing.techcup.competition.api.dto.GroupStandingsResponse;
import edu.escuelaing.techcup.competition.api.dto.StandingRow;
import edu.escuelaing.techcup.competition.domain.GroupTeam;
import edu.escuelaing.techcup.competition.domain.Match;
import edu.escuelaing.techcup.competition.domain.MatchPhase;
import edu.escuelaing.techcup.competition.domain.MatchStatus;
import edu.escuelaing.techcup.competition.domain.Standings;
import edu.escuelaing.techcup.competition.infrastructure.GroupTeamRepository;
import edu.escuelaing.techcup.competition.infrastructure.MatchRepository;
import edu.escuelaing.techcup.competition.infrastructure.TournamentGroupRepository;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.teams.application.TeamService;
import edu.escuelaing.techcup.tournaments.application.TournamentService;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The group-stage tables. Nothing is persisted: the rows are recomputed from the PLAYED GROUP
 * matches every time they are asked for, so correcting a result immediately corrects the table.
 * The arithmetic and the ordering live in the pure domain rule {@link Standings}.
 * <ul>
 *   <li><b>General table</b>: every approved team, every PLAYED group match (with or without a
 *       group, so tournaments played before groups existed keep their single table).</li>
 *   <li><b>Group tables</b>: one per group, with the teams of the group and only the PLAYED
 *       matches of that group. Teams may have played a different number of matches.</li>
 * </ul>
 *
 * <p>The knockout matches, however, are created <b>once</b>, from the pairings the organizer
 * confirms ({@code KnockoutService}). Correcting a group result afterwards is allowed (the
 * organizer keeps full correction power) but does <b>not</b> change the existing knockout matches:
 * if the qualifiers change, the knockout phases must be undone and built again.
 */
@Service
public class StandingsService {

    /** One group table, in the order of the groups. */
    public record GroupTable(Long groupId, String name, List<Standings.Row> rows) {
    }

    private final MatchRepository matches;
    private final TournamentGroupRepository groups;
    private final GroupTeamRepository groupTeams;
    private final TournamentService tournamentService;
    private final TeamService teamService;

    public StandingsService(MatchRepository matches, TournamentGroupRepository groups, GroupTeamRepository groupTeams,
                            TournamentService tournamentService, TeamService teamService) {
        this.matches = matches;
        this.groups = groups;
        this.groupTeams = groupTeams;
        this.tournamentService = tournamentService;
        this.teamService = teamService;
    }

    /** The general table (public; 404 for a suspended tournament unless the viewer is staff). */
    @Transactional(readOnly = true)
    public List<StandingRow> standings(Long tournamentId, AuthenticatedUser viewer) {
        tournamentService.requireVisible(tournamentId, viewer);
        return standings(tournamentId);
    }

    /** The general table, without a visibility check (the home page only shows the current tournament). */
    @Transactional(readOnly = true)
    public List<StandingRow> standings(Long tournamentId) {
        return rows(tournamentId).stream().map(StandingRow::from).toList();
    }

    /** One table per group, groups in order (public; 404 for a suspended tournament unless staff). */
    @Transactional(readOnly = true)
    public List<GroupStandingsResponse> groupStandings(Long tournamentId, AuthenticatedUser viewer) {
        tournamentService.requireVisible(tournamentId, viewer);
        return groupTables(tournamentId).stream()
                .map(table -> new GroupStandingsResponse(table.groupId(), table.name(),
                        table.rows().stream().map(StandingRow::from).toList()))
                .toList();
    }

    /** Domain rows of the general table, also used to seed a knockout when there are no groups. */
    @Transactional(readOnly = true)
    public List<Standings.Row> rows(Long tournamentId) {
        tournamentService.requireTournament(tournamentId);

        List<Standings.Competitor> competitors = tournamentService.approvedTeamIds(tournamentId).stream()
                .map(teamId -> new Standings.Competitor(teamId, teamService.requireTeam(teamId).getName()))
                .toList();

        List<Standings.Result> results = playedGroupMatches(tournamentId).stream()
                .map(StandingsService::toResult)
                .toList();

        return Standings.compute(competitors, results);
    }

    /** Domain rows of every group table, groups in order; also used to suggest the knockout. */
    @Transactional(readOnly = true)
    public List<GroupTable> groupTables(Long tournamentId) {
        Map<Long, List<GroupTeam>> teamsByGroup = groupTeams.findByTournament(tournamentId).stream()
                .collect(Collectors.groupingBy(assignment -> assignment.getGroup().getId()));
        Map<Long, List<Match>> matchesByGroup = playedGroupMatches(tournamentId).stream()
                .filter(match -> match.getGroup() != null)
                .collect(Collectors.groupingBy(match -> match.getGroup().getId()));
        return groups.findByTournamentIdOrderByPositionAsc(tournamentId).stream()
                .map(group -> {
                    List<Standings.Competitor> competitors = teamsByGroup.getOrDefault(group.getId(), List.of()).stream()
                            .map(assignment -> new Standings.Competitor(assignment.getTeam().getId(),
                                    assignment.getTeam().getName()))
                            .toList();
                    List<Standings.Result> results = matchesByGroup.getOrDefault(group.getId(), List.of()).stream()
                            .map(StandingsService::toResult)
                            .toList();
                    return new GroupTable(group.getId(), group.getName(), Standings.compute(competitors, results));
                })
                .toList();
    }

    private List<Match> playedGroupMatches(Long tournamentId) {
        return matches.findByTournamentIdAndStatusOrderByScheduledAtAscIdAsc(tournamentId, MatchStatus.PLAYED).stream()
                .filter(match -> match.getPhase() == MatchPhase.GROUP)
                .filter(match -> match.getHomeScore() != null && match.getAwayScore() != null)
                .toList();
    }

    private static Standings.Result toResult(Match match) {
        return new Standings.Result(match.getHomeTeam().getId(), match.getAwayTeam().getId(),
                match.getHomeScore(), match.getAwayScore());
    }
}
