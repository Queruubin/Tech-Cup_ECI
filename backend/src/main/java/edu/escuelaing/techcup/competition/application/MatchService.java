package edu.escuelaing.techcup.competition.application;

import edu.escuelaing.techcup.competition.api.dto.BracketResponse;
import edu.escuelaing.techcup.competition.api.dto.MatchResponse;
import edu.escuelaing.techcup.competition.api.dto.RecordResultRequest;
import edu.escuelaing.techcup.competition.api.dto.UpdateMatchRequest;
import edu.escuelaing.techcup.competition.domain.CancelReason;
import edu.escuelaing.techcup.competition.domain.EventType;
import edu.escuelaing.techcup.competition.domain.Match;
import edu.escuelaing.techcup.competition.domain.MatchEvent;
import edu.escuelaing.techcup.competition.domain.MatchPhase;
import edu.escuelaing.techcup.competition.domain.MatchStatus;
import edu.escuelaing.techcup.competition.domain.Standings;
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
 *       winner. Both are paired by {@link KnockoutFixtureStrategy}. A knockout draw with no
 *       penalties is rejected with an explanation instead of guessing who goes through.</li>
 *   <li><b>Update</b> (ORGANIZER): only kick-off time, venue and referee, only while the match is
 *       SCHEDULED, its current kick-off time is still in the future and the new one is not in
 *       the past; only ACTIVE referees may be appointed.</li>
 *   <li><b>Cancel</b> (ORGANIZER, tournament IN_PROGRESS): a soft delete that keeps the row with
 *       a {@link CancelReason}. A knockout match additionally needs the team that goes through
 *       (walkover). Cancelled matches never count for the standings.</li>
 *   <li><b>Result</b> (ORGANIZER): accepted exactly when {@link Match#isResultEditable} holds,
 *       i.e. the tournament is IN_PROGRESS and the match is SCHEDULED, or PLAYED with no later
 *       phase drawn from it yet (a correction replaces score, penalties and events). Every event
 *       must belong to one of the two teams and to a member of that team, and the number of
 *       goals reported per team must equal its score. A knockout draw requires a decisive
 *       penalty shoot-out.</li>
 * </ul>
 * Fixture generation locks the tournament row so two concurrent requests cannot both draw a
 * phase. Audited as MATCHES_GENERATED / MATCH_UPDATED / MATCH_CANCELLED / MATCH_RESULT_RECORDED
 * / MATCH_RESULT_CORRECTED.
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

    private final MatchRepository matches;
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

    public MatchService(MatchRepository matches, TournamentService tournamentService, TeamService teamService,
                        UserService userService, RefereeService refereeService, StandingsService standingsService,
                        RoundRobinFixtureStrategy roundRobinStrategy, KnockoutFixtureStrategy knockoutStrategy,
                        MatchResponseAssembler assembler, AuditService auditService, Clock clock) {
        this.matches = matches;
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
        List<Match> currentMatches = all.stream().filter(match -> match.getPhase() == currentPhase).toList();
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
     * The teams that went through, in bracket order: the winner of a played match, or the
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
     * first round in the past. Those matches could no longer be rescheduled by the organizer, and
     * captains could no longer submit a lineup for them, leaving the round permanently stuck.
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

    @Transactional
    public MatchResponse update(AuthenticatedUser actor, Long matchId, UpdateMatchRequest request) {
        Match match = requireMatch(matchId);
        if (match.getStatus() != MatchStatus.SCHEDULED) {
            throw new BusinessRuleException("Solo se puede reprogramar un partido programado; su estado actual es «"
                    + match.getStatus().label() + "».");
        }
        Instant now = Instant.now(clock);
        if (match.getScheduledAt() != null && !match.getScheduledAt().isAfter(now)) {
            throw new BusinessRuleException("Este partido ya inició y no se puede reprogramar.");
        }

        Map<String, Object> changes = new LinkedHashMap<>();
        if (request.scheduledAt() != null) {
            if (!request.scheduledAt().isAfter(now)) {
                throw new BusinessRuleException("La nueva hora de inicio debe ser posterior al momento actual.");
            }
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
            throw new BusinessRuleException("No hay nada que actualizar: indique una hora de inicio, una cancha o un árbitro.");
        }

        auditService.record(actor.id(), AuditAction.MATCH_UPDATED, ENTITY_TYPE, matchId, changes);
        return assembler.toResponse(match);
    }

    /**
     * Soft cancellation: the row stays with its reason so the history keeps the fixture.
     *
     * @param winnerTeamId the team that goes through (walkover); required for knockout matches,
     *                     ignored for group matches
     */
    @Transactional
    public MatchResponse cancel(AuthenticatedUser actor, Long matchId, CancelReason reason, Long winnerTeamId) {
        if (reason == null) {
            throw new BusinessRuleException("Debe indicar el motivo de la cancelación: descalificación o no presentación.");
        }
        Match match = requireMatch(matchId);
        requireInProgress(match, "cancelar");
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
        match.moveTo(MatchStatus.CANCELLED);
        match.setCancelReason(reason);
        match.setWalkoverWinnerTeam(walkoverWinner);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason.name());
        if (walkoverWinner != null) {
            details.put("walkoverWinnerTeamId", walkoverWinner.getId());
        }
        auditService.record(actor.id(), AuditAction.MATCH_CANCELLED, ENTITY_TYPE, matchId, details);
        return assembler.toResponse(match);
    }

    /** Records a result, or corrects one while no later phase has been drawn from it. */
    @Transactional
    public MatchResponse recordResult(AuthenticatedUser actor, Long matchId, RecordResultRequest request) {
        Match match = requireMatch(matchId);
        requireInProgress(match, "registrar el resultado de");
        boolean correction = match.getStatus() == MatchStatus.PLAYED;
        if (!match.isResultEditable(assembler.latestPhase(match.getTournament().getId()))) {
            throw new BusinessRuleException(correction
                    ? "El resultado ya no se puede corregir: la siguiente fase del torneo ya fue generada a partir de él."
                    : "Un partido en estado «" + match.getStatus().label() + "» no admite resultado.");
        }
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
        return assembler.toResponse(match);
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
