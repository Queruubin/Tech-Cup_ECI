package edu.escuelaing.techcup.competition.application;

import edu.escuelaing.techcup.competition.api.dto.BracketResponse;
import edu.escuelaing.techcup.competition.api.dto.MatchResponse;
import edu.escuelaing.techcup.competition.api.dto.PhaseUndoneResponse;
import edu.escuelaing.techcup.competition.api.dto.RecordResultRequest;
import edu.escuelaing.techcup.competition.api.dto.UpdateMatchRequest;
import edu.escuelaing.techcup.competition.domain.CancelReason;
import edu.escuelaing.techcup.competition.domain.EventType;
import edu.escuelaing.techcup.competition.domain.Match;
import edu.escuelaing.techcup.competition.domain.MatchEvent;
import edu.escuelaing.techcup.competition.domain.MatchPhase;
import edu.escuelaing.techcup.competition.domain.MatchStatus;
import edu.escuelaing.techcup.competition.domain.Standings;
import edu.escuelaing.techcup.competition.infrastructure.LineupRepository;
import edu.escuelaing.techcup.competition.infrastructure.MatchRepository;
import edu.escuelaing.techcup.identity.application.RefereeService;
import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.identity.domain.Role;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fixture and result use cases (spec 7.5). Rules enforced here:
 * <ul>
 *   <li><b>Generate</b> (ORGANIZER): the tournament must be IN_PROGRESS and have no match yet;
 *       the approved teams are drawn into a single round robin by
 *       {@link RoundRobinFixtureStrategy}. Venues and ACTIVE referees are handed out by cycling
 *       the available lists and each matchday kicks off at {@value #KICK_OFF_HOUR}:00, one day
 *       after the previous one, starting on the tournament's start date or on the first later
 *       day whose kick-off is still in the future (see {@link #firstAvailableMatchDay(LocalDate)}).</li>
 *   <li><b>Advance</b> (ORGANIZER): every match of the current phase must be PLAYED or CANCELLED.
 *       Coming out of the group stage the qualified teams are taken from the standings
 *       (8 or more teams &rarr; quarter-finals, 4 to 7 &rarr; semi-finals, fewer &rarr; final);
 *       afterwards the winners move on, a cancelled knockout match contributing its walkover
 *       winner, always in bracket order (the order the phase was drawn in, never kick-off order).
 *       Both are paired by {@link KnockoutFixtureStrategy}. A knockout draw with no penalties is
 *       rejected with an explanation instead of guessing who goes through.</li>
 *   <li><b>Undo phase</b> (ORGANIZER, tournament IN_PROGRESS): deletes every match of the latest
 *       phase when none of them is PLAYED, so it can be drawn again.</li>
 * </ul>
 * While the tournament is IN_PROGRESS the organizer has full correction power over its matches
 * (FINISHED is read-only):
 * <ul>
 *   <li><b>Update</b>: kick-off time (past values allowed, to record what happened), venue of the
 *       tournament, ACTIVE referee, for any status; the teams only while the match is not PLAYED
 *       (both approved in the tournament, distinct, and not already playing another non-cancelled
 *       match of the same group round or knockout phase, nor sent through that knockout phase by
 *       walkover). A team leaving a knockout match must not already stand in the next phase (undo
 *       it first). A replaced team loses its lineup.</li>
 *   <li><b>Cancel</b>: a soft delete that keeps the row with a {@link CancelReason}. A knockout
 *       match additionally needs the team that goes through (walkover). Cancelled matches never
 *       count for the standings.</li>
 *   <li><b>Result</b>: accepted exactly when {@link Match#isResultEditable()} holds (SCHEDULED, or
 *       PLAYED for a correction that replaces score, penalties and events), whatever later phases
 *       exist. Every event must belong to one of the two teams and to a member of that team, and
 *       the goals reported per team must equal its score. A knockout draw requires a decisive
 *       penalty shoot-out.</li>
 *   <li><b>Knockout propagation</b>: a knockout result or walkover whose winner is not the team
 *       holding the slot in the next phase replaces the loser there (see
 *       {@link #planPropagation(Match, Team)}), or refuses the whole request when that next match
 *       was already played.</li>
 *   <li><b>Reopen</b>: PLAYED or CANCELLED &rarr; SCHEDULED, clearing the recorded outcome.</li>
 * </ul>
 * Every mutating use case runs in one transaction, so a failure part-way (for example while
 * propagating a correction) leaves nothing half-written. Every mutating use case locks the
 * tournament row first (as {@code TournamentService.finish} does), so two concurrent requests
 * cannot both change the phases and no result slips into a tournament being finished. Audited as
 * MATCHES_GENERATED / PHASE_UNDONE / MATCH_UPDATED / MATCH_CANCELLED / MATCH_RESULT_RECORDED /
 * MATCH_RESULT_CORRECTED / MATCH_REOPENED.
 */
@Service
public class MatchService {

    static final String ENTITY_TYPE = "MATCH";
    /** Fixture generation is an event of the tournament, not of any single match. */
    static final String TOURNAMENT_ENTITY_TYPE = "TOURNAMENT";
    static final int KICK_OFF_HOUR = 18;
    private static final int TEAMS_FOR_QUARTERFINALS = 8;
    private static final int TEAMS_FOR_SEMIFINALS = 4;
    private static final int TEAMS_FOR_FINAL = 2;
    /** The order matches were drawn in, which is the bracket order of a knockout phase. */
    private static final Comparator<Match> BRACKET_ORDER =
            Comparator.comparing(Match::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    /** Audit reason of a next-phase match changed because an earlier knockout outcome changed. */
    static final String RESULT_CORRECTION = "RESULT_CORRECTION";

    private final MatchRepository matches;
    private final LineupRepository lineups;
    private final TournamentService tournamentService;
    private final TeamService teamService;
    private final UserService userService;
    private final RefereeService refereeService;
    private final StandingsService standingsService;
    private final RoundRobinFixtureStrategy roundRobinStrategy;
    private final KnockoutFixtureStrategy knockoutStrategy;
    private final MatchResponseAssembler assembler;
    private final AuditService auditService;
    private final Clock clock;

    public MatchService(MatchRepository matches, LineupRepository lineups, TournamentService tournamentService,
                        TeamService teamService,
                        UserService userService, RefereeService refereeService, StandingsService standingsService,
                        RoundRobinFixtureStrategy roundRobinStrategy, KnockoutFixtureStrategy knockoutStrategy,
                        MatchResponseAssembler assembler, AuditService auditService, Clock clock) {
        this.matches = matches;
        this.lineups = lineups;
        this.tournamentService = tournamentService;
        this.teamService = teamService;
        this.userService = userService;
        this.refereeService = refereeService;
        this.standingsService = standingsService;
        this.roundRobinStrategy = roundRobinStrategy;
        this.knockoutStrategy = knockoutStrategy;
        this.assembler = assembler;
        this.auditService = auditService;
        this.clock = clock;
    }

    // --- queries ---------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<MatchResponse> list(Long tournamentId, MatchPhase phase) {
        tournamentService.requireTournament(tournamentId);
        List<Match> found = phase == null
                ? matches.findByTournamentIdOrderByScheduledAtAscIdAsc(tournamentId)
                : matches.findByTournamentIdAndPhaseOrderByIdAsc(tournamentId, phase);
        return assembler.toResponses(found);
    }

    @Transactional(readOnly = true)
    public MatchResponse get(Long matchId) {
        return assembler.toResponse(requireMatch(matchId));
    }

    @Transactional(readOnly = true)
    public List<MatchResponse> refereeMatches(AuthenticatedUser actor) {
        return assembler.toResponses(matches.findByRefereeIdOrderByScheduledAtAscIdAsc(actor.id()));
    }

    /** PLAYED matches, most recently played first. */
    @Transactional(readOnly = true)
    public List<MatchResponse> history(Long tournamentId) {
        tournamentService.requireTournament(tournamentId);
        List<Match> played = new ArrayList<>(
                matches.findByTournamentIdAndStatusOrderByScheduledAtAscIdAsc(tournamentId, MatchStatus.PLAYED));
        played.sort(Comparator.comparing((Match match) -> match.getScheduledAt(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Match::getId, Comparator.reverseOrder()));
        return assembler.toResponses(played);
    }

    /** Every match of one team in a tournament, in playing order. */
    @Transactional(readOnly = true)
    public List<MatchResponse> teamResults(Long tournamentId, Long teamId) {
        tournamentService.requireTournament(tournamentId);
        teamService.requireTeam(teamId);
        return assembler.toResponses(matches.findOfTeam(tournamentId, teamId));
    }

    /** The next matches still to be played; used by the home page. */
    @Transactional(readOnly = true)
    public List<MatchResponse> upcoming(Long tournamentId, int limit) {
        return assembler.toResponses(matches.findByTournamentIdAndStatusOrderByScheduledAtAscIdAsc(
                tournamentId, MatchStatus.SCHEDULED, Limit.of(limit)));
    }

    @Transactional(readOnly = true)
    public BracketResponse bracket(Long tournamentId) {
        tournamentService.requireTournament(tournamentId);
        Map<MatchPhase, List<Match>> byPhase = new EnumMap<>(MatchPhase.class);
        matches.findByTournamentIdOrderByScheduledAtAscIdAsc(tournamentId)
                .forEach(match -> byPhase.computeIfAbsent(match.getPhase(), key -> new ArrayList<>()).add(match));
        List<BracketResponse.Phase> phases = byPhase.entrySet().stream()
                .map(entry -> new BracketResponse.Phase(entry.getKey(), assembler.toResponses(entry.getValue())))
                .toList();
        return new BracketResponse(phases);
    }

    @Transactional(readOnly = true)
    public Match requireMatch(Long matchId) {
        return matches.findById(matchId).orElseThrow(() -> NotFoundException.of("el partido", matchId));
    }

    // --- fixture generation ------------------------------------------------------------------

    @Transactional
    public List<MatchResponse> generate(AuthenticatedUser actor, Long tournamentId) {
        Tournament tournament = tournamentService.requireTournamentForUpdate(tournamentId);
        if (tournament.getStatus() != TournamentStatus.IN_PROGRESS) {
            throw new BusinessRuleException("El calendario solo se puede generar mientras el torneo esté en progreso; "
                    + "su estado actual es «" + tournament.getStatus().label() + "».");
        }
        if (matches.existsByTournamentId(tournamentId)) {
            throw new BusinessRuleException("El calendario de este torneo ya fue generado.");
        }
        List<Long> teamIds = tournamentService.approvedTeamIds(tournamentId);
        if (teamIds.size() < TEAMS_FOR_FINAL) {
            throw new BusinessRuleException("Se requieren al menos " + TEAMS_FOR_FINAL
                    + " equipos aprobados para generar el calendario (se encontraron " + teamIds.size() + ").");
        }

        List<Match> created = persist(tournament, MatchPhase.GROUP, roundRobinStrategy.generate(teamIds),
                0, firstAvailableMatchDay(tournament.getStartDate()));

        auditService.record(actor.id(), AuditAction.MATCHES_GENERATED, TOURNAMENT_ENTITY_TYPE, tournamentId,
                Map.of("tournamentId", tournamentId, "phase", MatchPhase.GROUP.name(),
                        "matches", created.size(), "teams", teamIds.size()));
        return assembler.toResponses(created);
    }

    @Transactional
    public List<MatchResponse> advance(AuthenticatedUser actor, Long tournamentId) {
        Tournament tournament = tournamentService.requireTournamentForUpdate(tournamentId);
        if (tournament.getStatus() != TournamentStatus.IN_PROGRESS) {
            throw new BusinessRuleException("Las llaves solo pueden avanzar mientras el torneo esté en progreso; "
                    + "su estado actual es «" + tournament.getStatus().label() + "».");
        }
        List<Match> all = matches.findByTournamentIdOrderByScheduledAtAscIdAsc(tournamentId);
        if (all.isEmpty()) {
            throw new BusinessRuleException("Genere la fase de grupos antes de avanzar a las fases eliminatorias.");
        }

        MatchPhase currentPhase = all.stream()
                .map(Match::getPhase)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        // Bracket order is creation order (id), never kick-off order: the organizer may reschedule a
        // match, and the fold of KnockoutFixtureStrategy must still pair the winners of the
        // original bracket slots.
        List<Match> currentMatches = all.stream()
                .filter(match -> match.getPhase() == currentPhase)
                .sorted(BRACKET_ORDER)
                .toList();
        long pending = currentMatches.stream().filter(match -> !match.getStatus().isFinished()).count();
        if (pending > 0) {
            throw new BusinessRuleException("La " + currentPhase.label() + " todavía tiene "
                    + Messages.plural(pending, "partido", "partidos") + " por jugar o cancelar.");
        }
        if (currentPhase == MatchPhase.FINAL) {
            throw new BusinessRuleException("La final ya se jugó; lo que sigue es finalizar el torneo.");
        }

        MatchPhase nextPhase;
        List<Long> qualified;
        if (currentPhase == MatchPhase.GROUP) {
            List<Standings.Row> table = standingsService.rows(tournamentId);
            int qualifierCount = qualifierCount(table.size());
            nextPhase = phaseFor(qualifierCount);
            qualified = table.stream().limit(qualifierCount).map(Standings.Row::teamId).toList();
        } else {
            nextPhase = currentPhase.nextKnockoutPhase();
            qualified = winnersOf(currentMatches);
        }

        int roundOffset = matches.findMaxRoundNumber(tournamentId);
        LocalDate lastDate = lastScheduledDate(all).orElse(tournament.getStartDate());
        List<Match> created = persist(tournament, nextPhase, knockoutStrategy.generate(qualified),
                roundOffset, firstAvailableMatchDay(lastDate.plusDays(1)));

        auditService.record(actor.id(), AuditAction.MATCHES_GENERATED, TOURNAMENT_ENTITY_TYPE, tournamentId,
                Map.of("tournamentId", tournamentId, "phase", nextPhase.name(), "matches", created.size()));
        return assembler.toResponses(created);
    }

    /**
     * Deletes every match of the latest phase of the tournament, as long as none of them has been
     * played, so the phase can be drawn again ({@code advance}, or {@code generate} when the group
     * stage is undone). Events and lineups go with their matches (FK {@code ON DELETE CASCADE}).
     */
    @Transactional
    public PhaseUndoneResponse undoPhase(AuthenticatedUser actor, Long tournamentId) {
        Tournament tournament = tournamentService.requireTournamentForUpdate(tournamentId);
        if (tournament.getStatus() != TournamentStatus.IN_PROGRESS) {
            throw new BusinessRuleException("Solo se puede deshacer una fase mientras el torneo esté en progreso; "
                    + "su estado actual es «" + tournament.getStatus().label() + "».");
        }
        List<Match> all = matches.findByTournamentIdOrderByScheduledAtAscIdAsc(tournamentId);
        MatchPhase latestPhase = all.stream()
                .map(Match::getPhase)
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new BusinessRuleException("El torneo todavía no tiene partidos: no hay ninguna fase "
                        + "para deshacer."));
        List<Match> phaseMatches = all.stream().filter(match -> match.getPhase() == latestPhase).toList();
        if (phaseMatches.stream().anyMatch(Match::isPlayed)) {
            throw new BusinessRuleException("No se puede deshacer la fase " + phaseName(latestPhase)
                    + ": ya tiene partidos jugados. Reábralos primero.");
        }

        matches.deleteAll(phaseMatches);

        auditService.record(actor.id(), AuditAction.PHASE_UNDONE, TOURNAMENT_ENTITY_TYPE, tournamentId,
                Map.of("tournamentId", tournamentId, "phase", latestPhase.name(), "deletedMatches", phaseMatches.size()));
        return new PhaseUndoneResponse(latestPhase, phaseMatches.size());
    }

    /** "de grupos" for the group stage, the phase label otherwise ("semifinal", "cuartos de final"). */
    private static String phaseName(MatchPhase phase) {
        return phase == MatchPhase.GROUP ? "de grupos" : phase.label();
    }

    /** 8 or more teams play quarter-finals, 4 to 7 play semi-finals, fewer go straight to the final. */
    private static int qualifierCount(int competitors) {
        if (competitors >= TEAMS_FOR_QUARTERFINALS) {
            return TEAMS_FOR_QUARTERFINALS;
        }
        if (competitors >= TEAMS_FOR_SEMIFINALS) {
            return TEAMS_FOR_SEMIFINALS;
        }
        if (competitors >= TEAMS_FOR_FINAL) {
            return TEAMS_FOR_FINAL;
        }
        throw new BusinessRuleException("Se requieren al menos " + TEAMS_FOR_FINAL
                + " equipos para jugar una fase eliminatoria (se encontraron " + competitors + ").");
    }

    private static MatchPhase phaseFor(int qualifierCount) {
        return switch (qualifierCount) {
            case TEAMS_FOR_QUARTERFINALS -> MatchPhase.QUARTERFINAL;
            case TEAMS_FOR_SEMIFINALS -> MatchPhase.SEMIFINAL;
            default -> MatchPhase.FINAL;
        };
    }

    /**
     * The teams that went through, in the order of {@code phaseMatches} (bracket order, see
     * {@link #BRACKET_ORDER}): the winner of a played match, or the
     * walkover winner of a cancelled one. Refuses to guess when a tie has no winner.
     */
    private static List<Long> winnersOf(List<Match> phaseMatches) {
        List<Long> winners = new ArrayList<>(phaseMatches.size());
        for (Match match : phaseMatches) {
            if (match.getStatus() == MatchStatus.CANCELLED) {
                if (match.getWalkoverWinnerTeam() == null) {
                    throw new BusinessRuleException("El partido " + match.getId() + " fue cancelado por "
                            + match.getCancelReason().label() + " sin indicar qué equipo avanza, así que las llaves "
                            + "no tienen un ganador para él.");
                }
                winners.add(match.getWalkoverWinnerTeam().getId());
                continue;
            }
            winners.add(match.winner()
                    .orElseThrow(() -> new BusinessRuleException("El partido " + match.getId()
                            + " terminó empatado y no tiene una tanda de penales definida; una llave eliminatoria "
                            + "necesita penales para decidir quién clasifica."))
                    .getId());
        }
        return winners;
    }

    /**
     * Persists the fixtures of one phase, cycling the venues and referees of the tournament and
     * spreading the matchdays one day apart from {@code firstMatchDay}.
     */
    private List<Match> persist(Tournament tournament, MatchPhase phase, List<FixtureStrategy.Fixture> fixtures,
                                int roundOffset, LocalDate firstMatchDay) {
        List<Venue> availableVenues = tournament.getVenues();
        List<AppUser> availableReferees = refereeService.activeReferees();
        Map<Long, Team> teamCache = new HashMap<>();

        List<Match> created = new ArrayList<>(fixtures.size());
        int index = 0;
        for (FixtureStrategy.Fixture fixture : fixtures) {
            int roundNumber = roundOffset + fixture.roundNumber();
            Match match = Match.builder()
                    .tournament(tournament)
                    .phase(phase)
                    .roundNumber(roundNumber)
                    .homeTeam(teamCache.computeIfAbsent(fixture.homeTeamId(), teamService::requireTeam))
                    .awayTeam(teamCache.computeIfAbsent(fixture.awayTeamId(), teamService::requireTeam))
                    .venue(availableVenues.isEmpty() ? null : availableVenues.get(index % availableVenues.size()))
                    .referee(availableReferees.isEmpty()
                            ? null
                            : availableReferees.get(index % availableReferees.size()))
                    .scheduledAt(kickOff(firstMatchDay.plusDays(fixture.roundNumber() - 1L)))
                    .status(MatchStatus.SCHEDULED)
                    .build();
            created.add(matches.save(match));
            index++;
        }
        return created;
    }

    /**
     * First matchday that can still be played: the preferred day, or the earliest later day whose
     * kick-off is still in the future.
     *
     * <p>Generating the fixture list after {@value #KICK_OFF_HOUR}:00 would otherwise place the
     * first round in the past, where captains can no longer submit a lineup for it until the
     * organizer moves every match by hand.
     */
    private LocalDate firstAvailableMatchDay(LocalDate preferred) {
        LocalDate today = LocalDate.now(clock);
        LocalDate earliest = kickOff(today).isAfter(clock.instant()) ? today : today.plusDays(1);
        return preferred.isAfter(earliest) ? preferred : earliest;
    }

    private Instant kickOff(LocalDate day) {
        return day.atTime(LocalTime.of(KICK_OFF_HOUR, 0)).atZone(clock.getZone()).toInstant();
    }

    /** The calendar day, in the application zone, of the latest kick-off among {@code all}. */
    private Optional<LocalDate> lastScheduledDate(List<Match> all) {
        return all.stream()
                .map(Match::getScheduledAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .map(instant -> instant.atZone(clock.getZone()).toLocalDate());
    }

    // --- match management --------------------------------------------------------------------

    /**
     * Corrects kick-off time, venue, referee and/or teams. Any status is accepted while the
     * tournament is in progress, and the kick-off time may be in the past (it records when the
     * match was actually played). The teams can only change while the match has no result.
     */
    @Transactional
    public MatchResponse update(AuthenticatedUser actor, Long matchId, UpdateMatchRequest request) {
        Match match = requireMatchLockingTournament(matchId);
        requireInProgress(match, "modificar");

        Map<String, Object> changes = new LinkedHashMap<>();
        if (request.changesTeams()) {
            changeTeams(match, request, changes);
        }
        if (request.scheduledAt() != null) {
            match.setScheduledAt(request.scheduledAt());
            changes.put("scheduledAt", request.scheduledAt().toString());
        }
        if (request.venueId() != null) {
            Venue venue = match.getTournament().getVenues().stream()
                    .filter(candidate -> candidate.getId().equals(request.venueId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessRuleException("La cancha " + request.venueId()
                            + " no pertenece a este torneo."));
            match.setVenue(venue);
            changes.put("venueId", venue.getId());
        }
        if (request.refereeId() != null) {
            AppUser referee = userService.getUser(request.refereeId());
            if (!referee.hasRole(Role.REFEREE)) {
                throw new BusinessRuleException("El usuario " + request.refereeId() + " no es árbitro.");
            }
            if (!referee.isActive()) {
                throw new BusinessRuleException("El árbitro " + referee.getFullName()
                        + " está inactivo y no puede ser asignado.");
            }
            match.setReferee(referee);
            changes.put("refereeId", referee.getId());
        }
        if (changes.isEmpty()) {
            throw new BusinessRuleException("No hay nada que actualizar: indique una hora de inicio, una cancha, "
                    + "un árbitro o los equipos.");
        }

        auditService.record(actor.id(), AuditAction.MATCH_UPDATED, ENTITY_TYPE, matchId, changes);
        return assembler.toResponse(match);
    }

    /**
     * Replaces one or both teams of a match without a result. Both teams must be approved in the
     * tournament, must differ, and a team entering the match must not already play another
     * non-cancelled match of the same group round or of the same knockout phase. The lineup of a
     * team that leaves the match is deleted. Records the change in {@code changes}.
     */
    private void changeTeams(Match match, UpdateMatchRequest request, Map<String, Object> changes) {
        if (!match.areTeamsEditable()) {
            throw new BusinessRuleException("Reabra el partido antes de cambiar los equipos: tiene un resultado registrado.");
        }
        Team oldHome = match.getHomeTeam();
        Team oldAway = match.getAwayTeam();
        Long homeId = request.homeTeamId() != null ? request.homeTeamId() : oldHome.getId();
        Long awayId = request.awayTeamId() != null ? request.awayTeamId() : oldAway.getId();
        if (homeId.equals(awayId)) {
            throw new BusinessRuleException("Un equipo no puede jugar contra sí mismo: elija dos equipos distintos.");
        }
        if (homeId.equals(oldHome.getId()) && awayId.equals(oldAway.getId())) {
            return;
        }

        Team newHome = teamOf(match, homeId);
        Team newAway = teamOf(match, awayId);
        List<Long> approved = tournamentService.approvedTeamIds(match.getTournament().getId());
        List<Match> samePhase = matches.findByTournamentIdAndPhaseOrderByIdAsc(
                match.getTournament().getId(), match.getPhase());
        for (Team team : List.of(newHome, newAway)) {
            if (!approved.contains(team.getId())) {
                throw new BusinessRuleException("El equipo '" + team.getName()
                        + "' no tiene una inscripción aprobada en este torneo.");
            }
            if (!match.involves(team.getId())) {
                requireNoOtherMatchInSlot(match, team, samePhase);
            }
        }
        Team walkoverWinner = match.getWalkoverWinnerTeam();
        if (walkoverWinner != null && !walkoverWinner.getId().equals(homeId) && !walkoverWinner.getId().equals(awayId)) {
            throw new BusinessRuleException("Reabra el partido antes de reemplazar a '" + walkoverWinner.getName()
                    + "': avanzó a la siguiente fase por walkover.");
        }
        if (match.getPhase().isKnockout()) {
            List<Team> leaving = new ArrayList<>(2);
            for (Team team : List.of(oldHome, oldAway)) {
                if (!team.getId().equals(homeId) && !team.getId().equals(awayId)) {
                    leaving.add(team);
                }
            }
            requireNotInNextPhase(match, leaving);
        }

        match.setHomeTeam(newHome);
        match.setAwayTeam(newAway);
        for (Team team : List.of(oldHome, oldAway)) {
            if (!match.involves(team.getId())) {
                lineups.deleteByMatchIdAndTeamId(match.getId(), team.getId());
            }
        }
        changes.put("previousHomeTeamId", oldHome.getId());
        changes.put("homeTeamId", newHome.getId());
        changes.put("previousAwayTeamId", oldAway.getId());
        changes.put("awayTeamId", newAway.getId());
    }

    /** The team with {@code teamId}: one of the two already in the match, or loaded (404 when unknown). */
    private Team teamOf(Match match, Long teamId) {
        if (match.getHomeTeam().getId().equals(teamId)) {
            return match.getHomeTeam();
        }
        if (match.getAwayTeam().getId().equals(teamId)) {
            return match.getAwayTeam();
        }
        return teamService.requireTeam(teamId);
    }

    /**
     * A team plays at most one match per group round and per knockout phase. A cancelled match
     * frees its slots, except in a knockout phase for its walkover winner, who went through from
     * it and so already occupies a place in that phase.
     */
    private static void requireNoOtherMatchInSlot(Match match, Team team, List<Match> samePhase) {
        boolean knockout = match.getPhase().isKnockout();
        boolean clash = samePhase.stream()
                .filter(other -> !other.getId().equals(match.getId()))
                .filter(other -> knockout || other.getRoundNumber() == match.getRoundNumber())
                .anyMatch(other -> occupiesSlot(other, team.getId(), knockout));
        if (clash) {
            throw new BusinessRuleException(match.getPhase().isKnockout()
                    ? "El equipo '" + team.getName() + "' ya juega otro partido de " + match.getPhase().label() + "."
                    : "El equipo '" + team.getName() + "' ya juega otro partido en la jornada "
                            + match.getRoundNumber() + " de la fase de grupos.");
        }
    }

    private static boolean occupiesSlot(Match other, Long teamId, boolean knockout) {
        if (other.getStatus() != MatchStatus.CANCELLED) {
            return other.involves(teamId);
        }
        return knockout && isWalkoverWinner(other, teamId);
    }

    private static boolean isWalkoverWinner(Match match, Long teamId) {
        return match.getWalkoverWinnerTeam() != null && match.getWalkoverWinnerTeam().getId().equals(teamId);
    }

    /**
     * A team leaving a knockout match must not already stand in the next phase (it got there from
     * this match before it was reopened): replacing it here would leave it in a slot it no longer
     * earned. The organizer has to undo the next phase first.
     */
    private void requireNotInNextPhase(Match match, List<Team> leaving) {
        MatchPhase nextPhase = match.getPhase().nextKnockoutPhase();
        if (nextPhase == null || leaving.isEmpty()) {
            return;
        }
        List<Match> nextMatches = matches.findByTournamentIdAndPhaseOrderByIdAsc(
                match.getTournament().getId(), nextPhase);
        for (Team team : leaving) {
            boolean present = nextMatches.stream()
                    .anyMatch(next -> next.involves(team.getId()) || isWalkoverWinner(next, team.getId()));
            if (present) {
                throw new BusinessRuleException("Deshaga la fase " + nextPhase.label()
                        + " antes de cambiar los equipos de este partido: " + team.getName() + " ya figura en ella.");
            }
        }
    }

    /**
     * Soft cancellation: the row stays with its reason so the history keeps the fixture. A
     * knockout walkover is propagated to the next phase exactly like a result (see
     * {@link #planPropagation(Match, Team)}).
     *
     * @param winnerTeamId the team that goes through (walkover); required for knockout matches,
     *                     ignored for group matches
     */
    @Transactional
    public MatchResponse cancel(AuthenticatedUser actor, Long matchId, CancelReason reason, Long winnerTeamId) {
        if (reason == null) {
            throw new BusinessRuleException("Debe indicar el motivo de la cancelación: descalificación o no presentación.");
        }
        Match match = requireMatchLockingTournament(matchId);
        requireInProgress(match, "cancelar");
        match.getStatus().transitionTo(MatchStatus.CANCELLED);
        Team walkoverWinner = null;
        if (match.getPhase().isKnockout()) {
            if (winnerTeamId == null) {
                throw new BusinessRuleException("Al cancelar un partido de " + match.getPhase().label()
                        + " debe indicar qué equipo avanza a la siguiente fase.");
            }
            if (!match.involves(winnerTeamId)) {
                throw new BusinessRuleException("El equipo " + winnerTeamId + " no juega este partido, así que no puede avanzar por él.");
            }
            walkoverWinner = match.getHomeTeam().getId().equals(winnerTeamId) ? match.getHomeTeam() : match.getAwayTeam();
        }
        Optional<BracketFix> bracketFix = walkoverWinner == null
                ? Optional.empty()
                : planPropagation(match, walkoverWinner);

        match.moveTo(MatchStatus.CANCELLED);
        match.setCancelReason(reason);
        match.setWalkoverWinnerTeam(walkoverWinner);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason.name());
        if (walkoverWinner != null) {
            details.put("walkoverWinnerTeamId", walkoverWinner.getId());
        }
        auditService.record(actor.id(), AuditAction.MATCH_CANCELLED, ENTITY_TYPE, matchId, details);
        bracketFix.ifPresent(fix -> applyPropagation(actor, match, fix));
        return assembler.toResponse(match);
    }

    /**
     * Records a result, or corrects a PLAYED one (score, penalties and events are replaced). The
     * consequences of a knockout result for the next phase are worked out before anything is
     * written: when that phase cannot follow it (see {@link #planPropagation(Match, Team)}) the
     * request is refused and nothing changes.
     */
    @Transactional
    public MatchResponse recordResult(AuthenticatedUser actor, Long matchId, RecordResultRequest request) {
        Match match = requireMatchLockingTournament(matchId);
        requireInProgress(match, "registrar el resultado de");
        if (!match.isResultEditable()) {
            throw new BusinessRuleException("Un partido en estado «" + match.getStatus().label()
                    + "» no admite resultado; reábralo primero.");
        }
        boolean correction = match.isPlayed();
        int homeScore = request.homeScore();
        int awayScore = request.awayScore();

        if (match.getPhase().isKnockout() && homeScore == awayScore) {
            Integer homePenalties = request.homePenalties();
            Integer awayPenalties = request.awayPenalties();
            if (homePenalties == null || awayPenalties == null) {
                throw new BusinessRuleException("Un partido de " + match.getPhase().label()
                        + " no puede terminar empatado: registre la tanda de penales.");
            }
            if (homePenalties.equals(awayPenalties)) {
                throw new BusinessRuleException("La tanda de penales debe tener un ganador (se registró "
                        + homePenalties + "-" + awayPenalties + ").");
            }
        }

        List<MatchEvent> events = buildEvents(match, request, homeScore, awayScore);
        Optional<BracketFix> bracketFix = match.getPhase().isKnockout()
                ? planPropagation(match, knockoutWinner(match, request))
                : Optional.empty();

        if (!correction) {
            match.moveTo(MatchStatus.PLAYED);
        }
        match.setHomeScore(homeScore);
        match.setAwayScore(awayScore);
        match.setHomePenalties(request.homePenalties());
        match.setAwayPenalties(request.awayPenalties());
        match.clearEvents();
        events.forEach(match::addEvent);

        auditService.record(actor.id(),
                correction ? AuditAction.MATCH_RESULT_CORRECTED : AuditAction.MATCH_RESULT_RECORDED,
                ENTITY_TYPE, matchId,
                Map.of("homeScore", homeScore, "awayScore", awayScore, "events", events.size()));
        bracketFix.ifPresent(fix -> applyPropagation(actor, match, fix));
        return assembler.toResponse(match);
    }

    /**
     * PLAYED or CANCELLED &rarr; SCHEDULED, clearing score, penalties, events, cancel reason and
     * walkover winner, so the organizer can fix the teams or record the match again.
     */
    @Transactional
    public MatchResponse reopen(AuthenticatedUser actor, Long matchId) {
        Match match = requireMatchLockingTournament(matchId);
        requireInProgress(match, "reabrir");
        if (!match.isReopenable()) {
            throw new BusinessRuleException("Solo se puede reabrir un partido jugado o cancelado; su estado actual es «"
                    + match.getStatus().label() + "».");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("previousStatus", match.getStatus().name());
        if (match.isPlayed()) {
            details.put("homeScore", match.getHomeScore());
            details.put("awayScore", match.getAwayScore());
        } else {
            details.put("cancelReason", match.getCancelReason() == null ? null : match.getCancelReason().name());
            if (match.getWalkoverWinnerTeam() != null) {
                details.put("walkoverWinnerTeamId", match.getWalkoverWinnerTeam().getId());
            }
        }

        match.reopen();

        auditService.record(actor.id(), AuditAction.MATCH_REOPENED, ENTITY_TYPE, matchId, details);
        return assembler.toResponse(match);
    }

    // --- knockout propagation -------------------------------------------------------------------

    /** Replace {@code loser} by {@code winner} in {@code target}, a match of the next knockout phase. */
    private record BracketFix(Match target, Team loser, Team winner) {
    }

    /**
     * Works out, <em>without changing anything</em>, how the next knockout phase must follow a new
     * outcome of {@code match}. It looks for the match of the next phase that holds one of the two
     * teams: when it already holds {@code winner} (or there is no next phase, or no such match)
     * nothing needs to change; when it holds the loser, the loser must be replaced by the winner.
     * That is refused while the next match is PLAYED, or CANCELLED with the loser sent through by
     * walkover, because its own outcome would silently become meaningless.
     *
     * @throws BusinessRuleException when the next phase cannot follow the new outcome
     */
    private Optional<BracketFix> planPropagation(Match match, Team winner) {
        MatchPhase nextPhase = match.getPhase().nextKnockoutPhase();
        if (nextPhase == null) {
            return Optional.empty();
        }
        Team loser = match.opponentOf(winner.getId());
        List<Match> nextMatches = matches.findByTournamentIdAndPhaseOrderByIdAsc(
                match.getTournament().getId(), nextPhase);
        if (nextMatches.stream().anyMatch(next -> next.involves(winner.getId()))) {
            return Optional.empty();
        }
        Optional<Match> holdingLoser = nextMatches.stream()
                .filter(next -> next.involves(loser.getId()))
                .min(Comparator.comparing((Match next) -> next.getStatus() == MatchStatus.CANCELLED));
        if (holdingLoser.isEmpty()) {
            return Optional.empty();
        }
        Match target = holdingLoser.get();
        if (target.isPlayed()) {
            throw new BusinessRuleException("El partido de " + nextPhase.label() + " ya se jugó con "
                    + loser.getName() + "; reábralo antes de corregir este resultado.");
        }
        Team targetWalkover = target.getWalkoverWinnerTeam();
        if (targetWalkover != null && targetWalkover.getId().equals(loser.getId())) {
            throw new BusinessRuleException("El partido de " + nextPhase.label() + " fue cancelado dando el pase a "
                    + loser.getName() + "; reábralo antes de corregir este resultado.");
        }
        return Optional.of(new BracketFix(target, loser, winner));
    }

    private void applyPropagation(AuthenticatedUser actor, Match source, BracketFix fix) {
        Match target = fix.target();
        target.replaceTeam(fix.loser(), fix.winner());
        lineups.deleteByMatchIdAndTeamId(target.getId(), fix.loser().getId());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", RESULT_CORRECTION);
        details.put("sourceMatchId", source.getId());
        details.put("previousTeamId", fix.loser().getId());
        details.put("teamId", fix.winner().getId());
        auditService.record(actor.id(), AuditAction.MATCH_UPDATED, ENTITY_TYPE, target.getId(), details);
    }

    /** The winner of a knockout result about to be recorded (the request was already validated). */
    private static Team knockoutWinner(Match match, RecordResultRequest request) {
        int home = request.homeScore();
        int away = request.awayScore();
        if (home == away) {
            home = request.homePenalties();
            away = request.awayPenalties();
        }
        return home > away ? match.getHomeTeam() : match.getAwayTeam();
    }

    /**
     * Loads a match for a mutating use case after locking its tournament row, so the in-progress
     * check cannot race {@code TournamentService.finish} (or {@code advance} / {@code undoPhase}):
     * the tournament is read under the lock before the match, so the status seen by
     * {@link #requireInProgress} is the committed one.
     */
    private Match requireMatchLockingTournament(Long matchId) {
        Long tournamentId = matches.findTournamentIdById(matchId)
                .orElseThrow(() -> NotFoundException.of("el partido", matchId));
        tournamentService.requireTournamentForUpdate(tournamentId);
        return requireMatch(matchId);
    }

    /** @param verb the Spanish infinitive phrase of what is being attempted, e.g. {@code "cancelar"} */
    private static void requireInProgress(Match match, String verb) {
        TournamentStatus status = match.getTournament().getStatus();
        if (status != TournamentStatus.IN_PROGRESS) {
            throw new BusinessRuleException("Solo se puede " + verb + " un partido mientras el torneo esté en progreso; "
                    + "su estado actual es «" + status.label() + "».");
        }
    }

    /**
     * Validates the reported events against the two teams and the score, and turns them into
     * entities. A goal must be scored by a member of one of the two teams, and the goals reported
     * for a team must add up exactly to its score.
     */
    private List<MatchEvent> buildEvents(Match match, RecordResultRequest request, int homeScore, int awayScore) {
        Team home = match.getHomeTeam();
        Team away = match.getAwayTeam();
        List<MatchEvent> events = new ArrayList<>();
        int homeGoals = 0;
        int awayGoals = 0;

        for (RecordResultRequest.Event reported : request.eventsOrEmpty()) {
            Team team;
            if (home.getId().equals(reported.teamId())) {
                team = home;
            } else if (away.getId().equals(reported.teamId())) {
                team = away;
            } else {
                throw new BusinessRuleException("El equipo " + reported.teamId() + " no juega este partido.");
            }
            if (!team.hasMember(reported.playerId())) {
                throw new BusinessRuleException("El jugador " + reported.playerId() + " no es integrante del equipo '"
                        + team.getName() + "'.");
            }
            if (reported.type() == EventType.GOAL) {
                if (team == home) {
                    homeGoals++;
                } else {
                    awayGoals++;
                }
            }
            events.add(MatchEvent.builder()
                    .team(team)
                    .player(userService.getUser(reported.playerId()))
                    .type(reported.type())
                    .minute(reported.minute())
                    .build());
        }

        if (homeGoals != homeScore) {
            throw new BusinessRuleException(goalMismatch(home.getName(), homeScore, homeGoals));
        }
        if (awayGoals != awayScore) {
            throw new BusinessRuleException(goalMismatch(away.getName(), awayScore, awayGoals));
        }
        return events;
    }

    /**
     * The reported score of a team does not match the goal events sent with it. Both halves of the
     * sentence agree in number with their own quantity, which is why they are composed here.
     */
    private static String goalMismatch(String teamName, int reportedScore, int goalEvents) {
        return Messages.agree(reportedScore, "Se registró", "Se registraron") + " "
                + Messages.plural(reportedScore, "gol", "goles") + " para el equipo '" + teamName
                + "', pero " + Messages.agree(goalEvents, "se envió", "se enviaron") + " "
                + Messages.plural(goalEvents, "evento de gol", "eventos de gol") + ".";
    }
}
