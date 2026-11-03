package edu.escuelaing.techcup.competition.application;

import edu.escuelaing.techcup.competition.api.dto.CreateMatchRequest;
import edu.escuelaing.techcup.competition.api.dto.GroupMatchesGeneratedResponse;
import edu.escuelaing.techcup.competition.api.dto.GroupResponse;
import edu.escuelaing.techcup.competition.api.dto.MatchResponse;
import edu.escuelaing.techcup.competition.domain.GroupTeam;
import edu.escuelaing.techcup.competition.domain.Match;
import edu.escuelaing.techcup.competition.domain.MatchPhase;
import edu.escuelaing.techcup.competition.domain.MatchStatus;
import edu.escuelaing.techcup.competition.domain.TournamentGroup;
import edu.escuelaing.techcup.competition.infrastructure.GroupTeamRepository;
import edu.escuelaing.techcup.competition.infrastructure.MatchRepository;
import edu.escuelaing.techcup.competition.infrastructure.TournamentGroupRepository;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.Messages;
import edu.escuelaing.techcup.shared.exception.NotFoundException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.teams.application.TeamService;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.tournaments.application.TournamentService;
import edu.escuelaing.techcup.tournaments.domain.Tournament;
import edu.escuelaing.techcup.tournaments.domain.TournamentStatus;
import edu.escuelaing.techcup.tournaments.domain.Venue;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The group stage, run by hand. Rules enforced here:
 * <ul>
 *   <li><b>Groups</b> (ORGANIZER, ADMIN inherits): created while the tournament is ACTIVE or
 *       IN_PROGRESS and the knockout has not started, named "Grupo A", "Grupo B", ... (the first
 *       free letter, so in creation order). A group may be deleted while it has no team and no
 *       match.</li>
 *   <li><b>Assignments</b>: only APPROVED teams, at most one group per team and tournament. A
 *       team may be moved to another group or removed from its group only while it has no match
 *       in that group (any status).</li>
 *   <li><b>Group matches</b> (tournament IN_PROGRESS, knockout not started): both teams of the
 *       group, distinct, and at most one match per pairing in the group (either orientation,
 *       whatever its status). The kick-off must lie in the years 2000 to 2100; venue and referee
 *       are optional.</li>
 *   <li><b>All against all in one group</b>: creates only the pairings the group is still missing,
 *       drawn by {@link RoundRobinFixtureStrategy}, one matchday per day at
 *       {@value MatchService#KICK_OFF_HOUR}:00 with venues and referees cycled, like the rest of
 *       the fixture. Needs at least two teams in the group.</li>
 *   <li><b>All groups at once</b> ("Generar partidos", hybrid fixture): the same round robin in
 *       every group with at least two teams, in one transaction. Same preconditions as a group
 *       match, plus at least one group with two teams and every APPROVED team assigned to a group
 *       (409 naming the teams without one). Idempotent: when no pairing is missing nothing is
 *       created and the answer says so. The organizer keeps full control afterwards (delete a
 *       match, create more, edit them).</li>
 *   <li><b>Deleting a group match</b>: only a SCHEDULED or CANCELLED match of the group stage (no
 *       result), with the same preconditions as creating one; lineups go with it (FK cascade).</li>
 * </ul>
 * The matchday ({@code roundNumber}) of a group match is the next matchday of the busier of its
 * two teams inside the group. Every mutation locks the tournament row first, refuses a suspended
 * tournament and is audited (GROUP_CREATED / GROUP_DELETED / GROUP_TEAM_ASSIGNED /
 * GROUP_TEAM_REMOVED / MATCH_CREATED / MATCHES_GENERATED / MATCH_DELETED).
 */
@Service
public class GroupStageService {

    static final String GROUP_ENTITY_TYPE = "TOURNAMENT_GROUP";

    private final TournamentGroupRepository groups;
    private final GroupTeamRepository groupTeams;
    private final MatchRepository matches;
    private final TournamentService tournamentService;
    private final TeamService teamService;
    private final MatchService matchService;
    private final RoundRobinFixtureStrategy roundRobinStrategy;
    private final MatchResponseAssembler assembler;
    private final AuditService auditService;

    public GroupStageService(TournamentGroupRepository groups, GroupTeamRepository groupTeams, MatchRepository matches,
                             TournamentService tournamentService, TeamService teamService, MatchService matchService,
                             RoundRobinFixtureStrategy roundRobinStrategy, MatchResponseAssembler assembler,
                             AuditService auditService) {
        this.groups = groups;
        this.groupTeams = groupTeams;
        this.matches = matches;
        this.tournamentService = tournamentService;
        this.teamService = teamService;
        this.matchService = matchService;
        this.roundRobinStrategy = roundRobinStrategy;
        this.assembler = assembler;
        this.auditService = auditService;
    }

    // --- queries ---------------------------------------------------------------------------

    /** The groups of a tournament in order (public; 404 for a suspended one unless staff). */
    @Transactional(readOnly = true)
    public List<GroupResponse> list(Long tournamentId, AuthenticatedUser viewer) {
        tournamentService.requireVisible(tournamentId, viewer);
        return responses(tournamentId);
    }

    // --- groups ----------------------------------------------------------------------------

    @Transactional
    public GroupResponse create(AuthenticatedUser actor, Long tournamentId) {
        Tournament tournament = tournamentService.requireTournamentForUpdate(tournamentId);
        requireGroupsEditable(tournament, "crear grupos");
        Set<Integer> used = groups.findByTournamentIdOrderByPositionAsc(tournamentId).stream()
                .map(TournamentGroup::getPosition)
                .collect(Collectors.toSet());
        int position = 0;
        while (used.contains(position)) {
            position++;
        }
        if (position >= TournamentGroup.MAX_GROUPS) {
            throw new BusinessRuleException("Un torneo admite como máximo " + TournamentGroup.MAX_GROUPS + " grupos.");
        }
        TournamentGroup group = groups.save(TournamentGroup.builder()
                .tournament(tournament)
                .name(TournamentGroup.nameFor(position))
                .position(position)
                .build());
        auditService.record(actor.id(), AuditAction.GROUP_CREATED, GROUP_ENTITY_TYPE, group.getId(),
                Map.of("tournamentId", tournamentId, "name", group.getName()));
        return response(tournamentId, group);
    }

    @Transactional
    public void delete(AuthenticatedUser actor, Long tournamentId, Long groupId) {
        Tournament tournament = tournamentService.requireTournamentForUpdate(tournamentId);
        requireGroupsEditable(tournament, "eliminar grupos");
        TournamentGroup group = requireGroup(tournamentId, groupId);
        if (matches.existsByGroupId(groupId)) {
            throw new BusinessRuleException("El " + group.getName() + " ya tiene partidos y no se puede eliminar.");
        }
        if (groupTeams.existsByGroupId(groupId)) {
            throw new BusinessRuleException("Quite primero los equipos del " + group.getName()
                    + "; solo se puede eliminar un grupo vacío.");
        }
        groups.delete(group);
        auditService.record(actor.id(), AuditAction.GROUP_DELETED, GROUP_ENTITY_TYPE, groupId,
                Map.of("tournamentId", tournamentId, "name", group.getName()));
    }

    /**
     * Puts an APPROVED team in the group, moving it out of the group it was in (refused when it
     * already plays a match there). Assigning a team to the group it is already in changes nothing.
     */
    @Transactional
    public GroupResponse assignTeam(AuthenticatedUser actor, Long tournamentId, Long groupId, Long teamId) {
        Tournament tournament = tournamentService.requireTournamentForUpdate(tournamentId);
        requireGroupsEditable(tournament, "modificar los grupos");
        TournamentGroup group = requireGroup(tournamentId, groupId);
        Team team = teamService.requireTeam(teamId);
        Long registrationId = tournamentService.approvedRegistrationId(tournamentId, teamId)
                .orElseThrow(() -> new BusinessRuleException("El equipo '" + team.getName()
                        + "' no tiene una inscripción aprobada en este torneo."));

        GroupTeam assignment = groupTeams.findByTournamentIdAndTeamId(tournamentId, teamId).orElse(null);
        if (assignment != null && assignment.getGroup().getId().equals(groupId)) {
            return response(tournamentId, group);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tournamentId", tournamentId);
        details.put("teamId", teamId);
        if (assignment == null) {
            groupTeams.save(GroupTeam.builder()
                    .group(group)
                    .tournamentId(tournamentId)
                    .team(team)
                    .registrationId(registrationId)
                    .build());
        } else {
            TournamentGroup previous = assignment.getGroup();
            requireNoMatchesInGroup(previous, team, "mover");
            assignment.setGroup(group);
            details.put("previousGroupId", previous.getId());
        }
        auditService.record(actor.id(), AuditAction.GROUP_TEAM_ASSIGNED, GROUP_ENTITY_TYPE, groupId, details);
        return response(tournamentId, group);
    }

    @Transactional
    public GroupResponse removeTeam(AuthenticatedUser actor, Long tournamentId, Long groupId, Long teamId) {
        Tournament tournament = tournamentService.requireTournamentForUpdate(tournamentId);
        requireGroupsEditable(tournament, "modificar los grupos");
        TournamentGroup group = requireGroup(tournamentId, groupId);
        Team team = teamService.requireTeam(teamId);
        GroupTeam assignment = groupTeams.findByTournamentIdAndTeamId(tournamentId, teamId)
                .filter(candidate -> candidate.getGroup().getId().equals(groupId))
                .orElseThrow(() -> new BusinessRuleException("El equipo '" + team.getName() + "' no pertenece al "
                        + group.getName() + "."));
        requireNoMatchesInGroup(group, team, "quitar");
        groupTeams.delete(assignment);
        auditService.record(actor.id(), AuditAction.GROUP_TEAM_REMOVED, GROUP_ENTITY_TYPE, groupId,
                Map.of("tournamentId", tournamentId, "teamId", teamId));
        return response(tournamentId, group);
    }

    // --- group matches ---------------------------------------------------------------------

    @Transactional
    public MatchResponse createMatch(AuthenticatedUser actor, Long tournamentId, CreateMatchRequest request) {
        Tournament tournament = tournamentService.requireTournamentForUpdate(tournamentId);
        requireGroupMatchesAllowed(tournament, "crear partidos");
        TournamentGroup group = requireGroup(tournamentId, request.groupId());
        MatchService.requireKickOffInRange(request.scheduledAt());
        Team home = teamService.requireTeam(request.homeTeamId());
        Team away = teamService.requireTeam(request.awayTeamId());
        matchService.requireGroupPairing(group, home, away, null);
        Venue venue = request.venueId() == null ? null : MatchService.requireVenueOf(tournament, request.venueId());
        AppUser referee = request.refereeId() == null ? null : matchService.requireActiveReferee(request.refereeId());

        Map<Long, Integer> played = matchesPerTeam(matches.findByGroupIdOrderByIdAsc(group.getId()));
        Match match = matches.save(Match.builder()
                .tournament(tournament)
                .phase(MatchPhase.GROUP)
                .group(group)
                .roundNumber(nextMatchday(played, home.getId(), away.getId()))
                .homeTeam(home)
                .awayTeam(away)
                .venue(venue)
                .referee(referee)
                .scheduledAt(request.scheduledAt())
                .status(MatchStatus.SCHEDULED)
                .build());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tournamentId", tournamentId);
        details.put("groupId", group.getId());
        details.put("homeTeamId", home.getId());
        details.put("awayTeamId", away.getId());
        details.put("scheduledAt", request.scheduledAt().toString());
        auditService.record(actor.id(), AuditAction.MATCH_CREATED, MatchService.ENTITY_TYPE, match.getId(), details);
        return assembler.toResponse(match);
    }

    /** Creates every pairing the group is still missing; refused when there is none, or fewer than two teams. */
    @Transactional
    public List<MatchResponse> roundRobin(AuthenticatedUser actor, Long tournamentId, Long groupId) {
        Tournament tournament = tournamentService.requireTournamentForUpdate(tournamentId);
        requireGroupMatchesAllowed(tournament, "crear partidos");
        TournamentGroup group = requireGroup(tournamentId, groupId);
        List<Long> teamIds = groupTeams.findTeamIdsByGroupId(groupId);
        if (teamIds.size() < 2) {
            throw new BusinessRuleException("El " + group.getName() + " necesita al menos 2 equipos para generar "
                    + "sus partidos (tiene " + teamIds.size() + ").");
        }
        List<Match> created = generateMissing(tournament, group, teamIds);
        if (created.isEmpty()) {
            throw new BusinessRuleException("El " + group.getName()
                    + " ya tiene un partido para cada pareja de equipos.");
        }
        auditService.record(actor.id(), AuditAction.MATCHES_GENERATED, MatchService.TOURNAMENT_ENTITY_TYPE,
                tournamentId, Map.of("tournamentId", tournamentId, "phase", MatchPhase.GROUP.name(),
                        "groupId", groupId, "matches", created.size(), "teams", teamIds.size()));
        return assembler.toResponses(created);
    }

    /**
     * "Generar partidos": the round robin of every group at once (only the pairings each group is
     * missing, scheduled exactly like {@link #roundRobin}). Refused unless at least one group has
     * two teams and every APPROVED team of the tournament is in a group; idempotent (nothing missing
     * &rarr; nothing created, and the message says so).
     */
    @Transactional
    public GroupMatchesGeneratedResponse generateAll(AuthenticatedUser actor, Long tournamentId) {
        Tournament tournament = tournamentService.requireTournamentForUpdate(tournamentId);
        requireGroupMatchesAllowed(tournament, "generar los partidos");
        List<TournamentGroup> tournamentGroups = groups.findByTournamentIdOrderByPositionAsc(tournamentId);
        Map<Long, List<Long>> teamsByGroup = new HashMap<>();
        Set<Long> assigned = new HashSet<>();
        for (GroupTeam assignment : groupTeams.findByTournament(tournamentId)) {
            teamsByGroup.computeIfAbsent(assignment.getGroup().getId(), key -> new ArrayList<>())
                    .add(assignment.getTeam().getId());
            assigned.add(assignment.getTeam().getId());
        }
        if (tournamentGroups.stream().noneMatch(group -> teamsByGroup.getOrDefault(group.getId(), List.of()).size() >= 2)) {
            throw new BusinessRuleException("Para generar los partidos se necesita al menos un grupo con 2 equipos.");
        }
        List<String> unassigned = tournamentService.approvedTeamIds(tournamentId).stream()
                .filter(teamId -> !assigned.contains(teamId))
                .map(teamId -> "'" + teamService.requireTeam(teamId).getName() + "'")
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        if (!unassigned.isEmpty()) {
            throw new BusinessRuleException("Asigne todos los equipos aprobados a un grupo antes de generar los "
                    + "partidos. " + (unassigned.size() == 1 ? "Falta: " : "Faltan: ") + String.join(", ", unassigned) + ".");
        }

        List<Match> created = new ArrayList<>();
        List<GroupMatchesGeneratedResponse.GroupCount> counts = new ArrayList<>();
        for (TournamentGroup group : tournamentGroups) {
            // Group members in assignment order, as the per-group round robin reads them.
            List<Long> teamIds = groupTeams.findTeamIdsByGroupId(group.getId());
            List<Match> ofGroup = teamIds.size() < 2 ? List.of() : generateMissing(tournament, group, teamIds);
            created.addAll(ofGroup);
            counts.add(new GroupMatchesGeneratedResponse.GroupCount(group.getId(), group.getName(), teamIds.size(),
                    ofGroup.size()));
        }
        if (created.isEmpty()) {
            return new GroupMatchesGeneratedResponse(0, counts, List.of(),
                    "Todos los grupos ya tienen un partido para cada pareja de equipos: no se creó ningún partido.");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tournamentId", tournamentId);
        details.put("phase", MatchPhase.GROUP.name());
        details.put("allGroups", true);
        details.put("matches", created.size());
        details.put("groups", counts.stream().filter(count -> count.created() > 0).count());
        auditService.record(actor.id(), AuditAction.MATCHES_GENERATED, MatchService.TOURNAMENT_ENTITY_TYPE,
                tournamentId, details);
        long groupsWithNew = counts.stream().filter(count -> count.created() > 0).count();
        return new GroupMatchesGeneratedResponse(created.size(), counts, assembler.toResponses(created),
                "Se " + (created.size() == 1 ? "creó " : "crearon ") + Messages.plural(created.size(), "partido", "partidos")
                        + " en " + Messages.plural(groupsWithNew, "grupo", "grupos") + ".");
    }

    /**
     * Deletes a group match that has no result (SCHEDULED or CANCELLED), so the organizer can
     * rebuild the fixture by hand after generating it. Same preconditions as creating one.
     */
    @Transactional
    public void deleteMatch(AuthenticatedUser actor, Long tournamentId, Long matchId) {
        Tournament tournament = tournamentService.requireTournamentForUpdate(tournamentId);
        Match match = matches.findById(matchId)
                .filter(candidate -> candidate.getTournament().getId().equals(tournamentId))
                .orElseThrow(() -> NotFoundException.of("el partido", matchId));
        if (match.getPhase() != MatchPhase.GROUP) {
            throw new BusinessRuleException("Solo se pueden eliminar partidos de la fase de grupos; las fases "
                    + "eliminatorias se deshacen completas con «Deshacer última fase».");
        }
        requireGroupMatchesAllowed(tournament, "eliminar partidos");
        if (match.isPlayed()) {
            throw new BusinessRuleException("El partido " + match.getHomeTeam().getName() + " vs "
                    + match.getAwayTeam().getName() + " ya tiene un resultado: reábralo antes de eliminarlo.");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tournamentId", tournamentId);
        details.put("groupId", match.getGroup() == null ? null : match.getGroup().getId());
        details.put("status", match.getStatus().name());
        details.put("homeTeamId", match.getHomeTeam().getId());
        details.put("awayTeamId", match.getAwayTeam().getId());
        details.put("scheduledAt", match.getScheduledAt() == null ? null : match.getScheduledAt().toString());
        matches.delete(match);
        auditService.record(actor.id(), AuditAction.MATCH_DELETED, MatchService.ENTITY_TYPE, matchId, details);
    }

    /**
     * Persists the pairings {@code group} is still missing (possibly none), one day per matchday
     * of the draw, starting on the tournament's start date or the day after the latest kick-off of
     * the group (never in the past), with venues and referees cycled and never double-booked.
     */
    private List<Match> generateMissing(Tournament tournament, TournamentGroup group, List<Long> teamIds) {
        List<Match> existing = matches.findByGroupIdOrderByIdAsc(group.getId());
        List<FixtureStrategy.Fixture> missing = roundRobinStrategy.generate(teamIds).stream()
                .filter(fixture -> existing.stream()
                        .noneMatch(match -> match.isBetween(fixture.homeTeamId(), fixture.awayTeamId())))
                .toList();
        if (missing.isEmpty()) {
            return List.of();
        }

        // One day per matchday of the draw that still has a missing pairing, starting on the
        // tournament's start date or the day after the latest kick-off of the group (never in the past).
        LocalDate preferred = matchService.lastScheduledDate(existing)
                .map(day -> day.plusDays(1))
                .filter(day -> day.isAfter(tournament.getStartDate()))
                .orElse(tournament.getStartDate());
        LocalDate firstDay = matchService.firstAvailableMatchDay(preferred);
        Map<Integer, Integer> dayOfRound = new TreeMap<>();
        missing.forEach(fixture -> dayOfRound.putIfAbsent(fixture.roundNumber(), 0));
        int dayIndex = 0;
        for (Map.Entry<Integer, Integer> entry : dayOfRound.entrySet()) {
            entry.setValue(dayIndex++);
        }

        Map<Long, Integer> played = matchesPerTeam(existing);
        List<MatchService.PlannedMatch> planned = new ArrayList<>(missing.size());
        for (FixtureStrategy.Fixture fixture : missing) {
            int matchday = nextMatchday(played, fixture.homeTeamId(), fixture.awayTeamId());
            played.merge(fixture.homeTeamId(), 1, Integer::sum);
            played.merge(fixture.awayTeamId(), 1, Integer::sum);
            planned.add(new MatchService.PlannedMatch(matchday, fixture.homeTeamId(), fixture.awayTeamId(),
                    firstDay.plusDays(dayOfRound.get(fixture.roundNumber()))));
        }
        return matchService.persist(tournament, MatchPhase.GROUP, group, planned);
    }

    // --- helpers ---------------------------------------------------------------------------

    /**
     * Groups change while the tournament takes or plays its teams (ACTIVE or IN_PROGRESS) and the
     * knockout has not started.
     *
     * @param action the Spanish infinitive phrase of what is being attempted, e.g. {@code "crear grupos"}
     */
    private void requireGroupsEditable(Tournament tournament, String action) {
        tournament.requireNotSuspended(action);
        TournamentStatus status = tournament.getStatus();
        if (status != TournamentStatus.ACTIVE && status != TournamentStatus.IN_PROGRESS) {
            throw new BusinessRuleException("Los grupos solo se pueden modificar mientras el torneo esté activo o en "
                    + "progreso; su estado actual es «" + status.label() + "».");
        }
        requireKnockoutNotStarted(tournament);
    }

    /** Group matches are created while the tournament is IN_PROGRESS and the knockout has not started. */
    private void requireGroupMatchesAllowed(Tournament tournament, String action) {
        tournament.requireNotSuspended(action);
        if (tournament.getStatus() != TournamentStatus.IN_PROGRESS) {
            throw new BusinessRuleException("Solo se puede " + action + " de grupo mientras el torneo esté en "
                    + "progreso; su estado actual es «" + tournament.getStatus().label() + "».");
        }
        requireKnockoutNotStarted(tournament);
    }

    private void requireKnockoutNotStarted(Tournament tournament) {
        if (matches.existsByTournamentIdAndPhaseNot(tournament.getId(), MatchPhase.GROUP)) {
            throw new BusinessRuleException("La eliminatoria ya comenzó: la fase de grupos no admite cambios. Deshaga "
                    + "las fases eliminatorias para volver a ella.");
        }
    }

    /** @param verb {@code "mover"} or {@code "quitar"} */
    private void requireNoMatchesInGroup(TournamentGroup group, Team team, String verb) {
        if (matches.existsInGroupForTeam(group.getId(), team.getId())) {
            throw new BusinessRuleException("No se puede " + verb + " a '" + team.getName() + "' del " + group.getName()
                    + ": ya tiene partidos en ese grupo.");
        }
    }

    private TournamentGroup requireGroup(Long tournamentId, Long groupId) {
        return groups.findByIdAndTournamentId(groupId, tournamentId)
                .orElseThrow(() -> NotFoundException.of("el grupo", groupId));
    }

    /** How many matches of the group (any status) each team plays. */
    private static Map<Long, Integer> matchesPerTeam(List<Match> groupMatches) {
        Map<Long, Integer> played = new HashMap<>();
        for (Match match : groupMatches) {
            played.merge(match.getHomeTeam().getId(), 1, Integer::sum);
            played.merge(match.getAwayTeam().getId(), 1, Integer::sum);
        }
        return played;
    }

    /** The next matchday of the busier of the two teams inside the group. */
    private static int nextMatchday(Map<Long, Integer> played, Long homeTeamId, Long awayTeamId) {
        return Math.max(played.getOrDefault(homeTeamId, 0), played.getOrDefault(awayTeamId, 0)) + 1;
    }

    private GroupResponse response(Long tournamentId, TournamentGroup group) {
        List<GroupTeam> assignments = groupTeams.findByTournament(tournamentId).stream()
                .filter(assignment -> assignment.getGroup().getId().equals(group.getId()))
                .toList();
        return toResponse(group, assignments, matches.findByGroupIdOrderByIdAsc(group.getId()));
    }

    private List<GroupResponse> responses(Long tournamentId) {
        Map<Long, List<GroupTeam>> teamsByGroup = groupTeams.findByTournament(tournamentId).stream()
                .collect(Collectors.groupingBy(assignment -> assignment.getGroup().getId()));
        Map<Long, List<Match>> matchesByGroup = matches
                .findByTournamentIdAndPhaseOrderByIdAsc(tournamentId, MatchPhase.GROUP).stream()
                .filter(match -> match.getGroup() != null)
                .collect(Collectors.groupingBy(match -> match.getGroup().getId()));
        return groups.findByTournamentIdOrderByPositionAsc(tournamentId).stream()
                .map(group -> toResponse(group, teamsByGroup.getOrDefault(group.getId(), List.of()),
                        matchesByGroup.getOrDefault(group.getId(), List.of())))
                .toList();
    }

    private static GroupResponse toResponse(TournamentGroup group, List<GroupTeam> assignments,
                                            List<Match> groupMatches) {
        List<GroupResponse.Team> teams = assignments.stream()
                .map(GroupTeam::getTeam)
                .map(team -> new GroupResponse.Team(team.getId(), team.getName(), team.getColors(),
                        groupMatches.stream().anyMatch(match -> match.involves(team.getId()))))
                .toList();
        return new GroupResponse(group.getId(), group.getName(), group.getPosition(), teams, groupMatches.size());
    }
}
