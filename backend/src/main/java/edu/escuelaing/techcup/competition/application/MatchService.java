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
import edu.escuelaing.techcup.competition.domain.TournamentGroup;
import edu.escuelaing.techcup.competition.infrastructure.GroupTeamRepository;
import edu.escuelaing.techcup.competition.infrastructure.LineupRepository;
import edu.escuelaing.techcup.competition.infrastructure.MatchRepository;
import edu.escuelaing.techcup.identity.application.RefereeService;
import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.identity.domain.Role;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.InvalidRequestException;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fixture and result use cases (spec 7.5). The group matches are created by hand, group by group
 * ({@link GroupStageService}), and the first knockout round is built by the organizer from an
 * editable suggestion ({@link KnockoutService}). Rules enforced here:
 * <ul>
 *   <li><b>Later knockout rounds</b>: as soon as the last match of a knockout phase gets its
 *       outcome (result or walkover) and the next phase does not exist yet, the next phase is
 *       drawn automatically: the winners move on, a cancelled knockout match contributing its
 *       walkover winner, always in bracket order (the order the phase was drawn in, never
 *       kick-off order), paired by {@link KnockoutFixtureStrategy} (winner of the first match
 *       against the winner of the last one, and so on). {@code advance} does the same by hand
 *       (after an undo, for example); it never leaves the group stage. Venues and ACTIVE referees
 *       are handed out by cycling the available lists and each matchday kicks off at
 *       {@value #KICK_OFF_HOUR}:00, starting the day after the latest kick-off or on the first
 *       later day whose kick-off is still in the future (see {@link #firstAvailableMatchDay(LocalDate)}).
 *       A knockout draw with no penalties is rejected with an explanation instead of guessing who
 *       goes through.</li>
 *   <li><b>Undo phase</b> (ORGANIZER, tournament IN_PROGRESS): deletes every match of the latest
 *       phase when none of them is PLAYED, so it can be drawn again (undoing the first knockout
 *       round goes back to the group stage).</li>
 * </ul>
 * While the tournament is IN_PROGRESS the organizer has full correction power over its matches
 * (FINISHED is read-only):
 * <ul>
 *   <li><b>Update</b>: kick-off time (past values allowed, to record what happened), venue of the
 *       tournament, ACTIVE referee, for any status; the teams only while the match is not PLAYED
 *       (both approved in the tournament, distinct; in a match of a group both must belong to that
 *       group and must not meet in another match of it; in a legacy group match or a knockout
 *       match, not already playing another non-cancelled match of the same group round or
 *       knockout phase, nor sent through that knockout phase by walkover). A team leaving a
 *       knockout match must not already stand in the next phase (undo it first). A replaced team
 *       loses its lineup.</li>
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
 *
 * <p>Every public read takes the viewer: the matches of a SUSPENDED tournament are answered with
 * 404 to anybody but ADMIN/ORGANIZER, like the tournament itself.
 */
@Service
public class MatchService {

    static final String ENTITY_TYPE = "MATCH";
    /** Fixture generation is an event of the tournament, not of any single match. */
    static final String TOURNAMENT_ENTITY_TYPE = "TOURNAMENT";
    static final int KICK_OFF_HOUR = 18;
    static final Instant EARLIEST_KICK_OFF = Instant.parse("2000-01-01T00:00:00Z");
    static final Instant LATEST_KICK_OFF = Instant.parse("2101-01-01T00:00:00Z");
    /** The order matches were drawn in, which is the bracket order of a knockout phase. */
    private static final Comparator<Match> BRACKET_ORDER =
            Comparator.comparing(Match::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    /** 409 when a generated fixture would kick off after the window accepted for a match date. */
    static final String NO_DATES_LEFT = "No hay fechas disponibles antes del año 2101 para programar los partidos.";
    /** Audit reason of a next-phase match changed because an earlier knockout outcome changed. */
    static final String RESULT_CORRECTION = "RESULT_CORRECTION";

    private final MatchRepository matches;
    private final LineupRepository lineups;
    private final GroupTeamRepository groupTeams;
    private final TournamentService tournamentService;
    private final TeamService teamService;
    private final UserService userService;
    private final RefereeService refereeService;
    private final KnockoutFixtureStrategy knockoutStrategy;
    private final MatchResponseAssembler assembler;
    private final AuditService auditService;
    private final Clock clock;

    public MatchService(MatchRepository matches, LineupRepository lineups, GroupTeamRepository groupTeams,
                        TournamentService tournamentService, TeamService teamService,
                        UserService userService, RefereeService refereeService,
                        KnockoutFixtureStrategy knockoutStrategy,
                        MatchResponseAssembler assembler, AuditService auditService, Clock clock) {
        this.matches = matches;
        this.lineups = lineups;
        this.groupTeams = groupTeams;
        this.tournamentService = tournamentService;
        this.teamService = teamService;
        this.userService = userService;
        this.refereeService = refereeService;
        this.knockoutStrategy = knockoutStrategy;
        this.assembler = assembler;
        this.auditService = auditService;
        this.clock = clock;
    }

    // --- queries ---------------------------------------------------------------------------

    /** @param viewer the caller, or null for an anonymous visitor (see {@link TournamentService#requireVisible}) */
    @Transactional(readOnly = true)
    public List<MatchResponse> list(Long tournamentId, MatchPhase phase, AuthenticatedUser viewer) {
        tournamentService.requireVisible(tournamentId, viewer);
        List<Match> found = phase == null
                ? matches.findByTournamentIdOrderByScheduledAtAscIdAsc(tournamentId)
                : matches.findByTournamentIdAndPhaseOrderByIdAsc(tournamentId, phase);
        return assembler.toResponses(found);
    }

    /** One match; 404 when its tournament is suspended and the viewer is not staff. */
    @Transactional(readOnly = true)
    public MatchResponse get(Long matchId, AuthenticatedUser viewer) {
        return assembler.toResponse(requireVisibleMatch(matchId, viewer));
    }

    /**
     * A match the viewer may see: 404 when its tournament is suspended and the viewer is not staff,
     * exactly as if it did not exist. Every read of a single match (detail, lineups, sanctioned
     * players) goes through here.
     *
     * @param viewer the caller, or null for an anonymous visitor
     */
    @Transactional(readOnly = true)
    public Match requireVisibleMatch(Long matchId, AuthenticatedUser viewer) {
        Match match = requireMatch(matchId);
        if (match.getTournament().getStatus().isSuspended() && !TournamentService.isStaff(viewer)) {
            throw NotFoundException.of("el partido", matchId);
        }
        return match;
    }

    /** The referee's own matches, leaving out those of a suspended tournament unless the caller is staff. */
    @Transactional(readOnly = true)
    public List<MatchResponse> refereeMatches(AuthenticatedUser actor) {
        boolean staff = TournamentService.isStaff(actor);
        return assembler.toResponses(matches.findByRefereeIdOrderByScheduledAtAscIdAsc(actor.id()).stream()
                .filter(match -> staff || !match.getTournament().getStatus().isSuspended())
                .toList());
    }

    /** PLAYED matches, most recently played first. */
    @Transactional(readOnly = true)
    public List<MatchResponse> history(Long tournamentId, AuthenticatedUser viewer) {
        tournamentService.requireVisible(tournamentId, viewer);
        List<Match> played = new ArrayList<>(
                matches.findByTournamentIdAndStatusOrderByScheduledAtAscIdAsc(tournamentId, MatchStatus.PLAYED));
        played.sort(Comparator.comparing((Match match) -> match.getScheduledAt(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Match::getId, Comparator.reverseOrder()));
        return assembler.toResponses(played);
    }

    /** Every match of one team in a tournament, in playing order. */
    @Transactional(readOnly = true)
    public List<MatchResponse> teamResults(Long tournamentId, Long teamId, AuthenticatedUser viewer) {
        tournamentService.requireVisible(tournamentId, viewer);
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
    public BracketResponse bracket(Long tournamentId, AuthenticatedUser viewer) {
        tournamentService.requireVisible(tournamentId, viewer);
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

    // --- knockout rounds ---------------------------------------------------------------------

    /**
     * Draws the next knockout round by hand once every match of the current knockout phase is
     * PLAYED or CANCELLED. Normally unnecessary: the round is drawn automatically when the last
     * match of the phase gets its outcome; this is the way back after undoing it. It never leaves
     * the group stage: the first knockout round is built with {@link KnockoutService#create}.
     */
    @Transactional
    public List<MatchResponse> advance(AuthenticatedUser actor, Long tournamentId) {
        Tournament tournament = tournamentService.requireTournamentForUpdate(tournamentId);
        tournament.requireNotSuspended("avanzar de fase");
        if (tournament.getStatus() != TournamentStatus.IN_PROGRESS) {
            throw new BusinessRuleException("Las llaves solo pueden avanzar mientras el torneo esté en progreso; "
                    + "su estado actual es «" + tournament.getStatus().label() + "».");
        }
        List<Match> all = matches.findByTournamentIdOrderByScheduledAtAscIdAsc(tournamentId);
        MatchPhase currentPhase = all.stream()
                .map(Match::getPhase)
                .max(Comparator.naturalOrder())
                .orElse(MatchPhase.GROUP);
        if (currentPhase == MatchPhase.GROUP) {
            throw new BusinessRuleException("La eliminatoria no se genera sola: use «Armar eliminatoria» para elegir "
                    + "el tamaño, revisar los cruces sugeridos y confirmarlos.");
        }
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
        List<Match> created = drawNextRound(actor, tournament, currentPhase, winnersOf(currentMatches), all, false);
        return assembler.toResponses(created);
    }

    /**
     * After an outcome (result or walkover) of a knockout match: when it completed its phase, every
     * match of it has a winner and the next phase does not exist yet, draws the next phase. Does
     * nothing otherwise, so a result is never refused because of this step. That includes a
     * calendar with no day left before {@link #LATEST_KICK_OFF}: the result stays recorded and
     * {@code advance} (by hand) explains why the round cannot be drawn until a kick-off is moved
     * earlier.
     */
    private void advanceIfPhaseComplete(AuthenticatedUser actor, Match match) {
        MatchPhase phase = match.getPhase();
        MatchPhase nextPhase = phase.nextKnockoutPhase();
        if (nextPhase == null) {
            return;
        }
        Long tournamentId = match.getTournament().getId();
        List<Match> phaseMatches = matches.findByTournamentIdAndPhaseOrderByIdAsc(tournamentId, phase).stream()
                .sorted(BRACKET_ORDER)
                .toList();
        if (phaseMatches.isEmpty() || phaseMatches.size() % 2 != 0
                || phaseMatches.stream().anyMatch(candidate -> outcomeWinner(candidate).isEmpty())
                || !matches.findByTournamentIdAndPhaseOrderByIdAsc(tournamentId, nextPhase).isEmpty()) {
            return;
        }
        List<Long> winners = phaseMatches.stream()
                .map(candidate -> outcomeWinner(candidate).orElseThrow().getId())
                .toList();
        List<Match> all = matches.findByTournamentIdOrderByScheduledAtAscIdAsc(tournamentId);
        if (!fitsCalendar(nextRoundDay(match.getTournament(), all))) {
            return;
        }
        drawNextRound(actor, match.getTournament(), phase, winners, all, true);
    }

    /** Persists the round after {@code phase} from its winners (bracket order) and audits it. */
    private List<Match> drawNextRound(AuthenticatedUser actor, Tournament tournament, MatchPhase phase,
                                      List<Long> winners, List<Match> all, boolean automatic) {
        MatchPhase nextPhase = phase.nextKnockoutPhase();
        int roundOffset = matches.findMaxRoundNumber(tournament.getId());
        List<Match> created = persist(tournament, nextPhase, knockoutStrategy.generate(winners),
                roundOffset, nextRoundDay(tournament, all));

        auditService.record(actor.id(), AuditAction.MATCHES_GENERATED, TOURNAMENT_ENTITY_TYPE, tournament.getId(),
                Map.of("tournamentId", tournament.getId(), "phase", nextPhase.name(), "matches", created.size(),
                        "automatic", automatic));
        return created;
    }

    /** The day a new knockout round is played: the day after the latest kick-off, never in the past. */
    LocalDate nextRoundDay(Tournament tournament, List<Match> all) {
        LocalDate lastDate = lastScheduledDate(all).orElse(tournament.getStartDate());
        return firstAvailableMatchDay(lastDate.plusDays(1));
    }

    /** Whether a generated match can kick off on {@code day} (before {@link #LATEST_KICK_OFF}). */
    boolean fitsCalendar(LocalDate day) {
        return kickOff(day).isBefore(LATEST_KICK_OFF);
    }

    /** The team that went through: the winner of a played match or the walkover winner of a cancelled one. */
    private static Optional<Team> outcomeWinner(Match match) {
        if (match.getStatus() == MatchStatus.CANCELLED) {
            return Optional.ofNullable(match.getWalkoverWinnerTeam());
        }
        return match.winner();
    }

    /**
     * Deletes every match of the latest phase of the tournament, as long as none of them has been
     * played, so the phase can be drawn again (the knockout is rebuilt with
     * {@link KnockoutService#create}, the next round with {@code advance}; undoing the group stage
     * deletes its unplayed matches). Events and lineups go with their matches (FK
     * {@code ON DELETE CASCADE}).
     */
    @Transactional
    public PhaseUndoneResponse undoPhase(AuthenticatedUser actor, Long tournamentId) {
        Tournament tournament = tournamentService.requireTournamentForUpdate(tournamentId);
        tournament.requireNotSuspended("deshacer una fase");
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

    /** One match to be created: its matchday number, its two teams and the day it is played. */
    record PlannedMatch(int roundNumber, Long homeTeamId, Long awayTeamId, LocalDate day) {
    }

    /**
     * Persists the fixtures of one phase, cycling the venues and referees of the tournament and
     * spreading the matchdays one day apart from {@code firstMatchDay}.
     */
    List<Match> persist(Tournament tournament, MatchPhase phase, List<FixtureStrategy.Fixture> fixtures,
                        int roundOffset, LocalDate firstMatchDay) {
        List<PlannedMatch> planned = fixtures.stream()
                .map(fixture -> new PlannedMatch(roundOffset + fixture.roundNumber(), fixture.homeTeamId(),
                        fixture.awayTeamId(), firstMatchDay.plusDays(fixture.roundNumber() - 1L)))
                .toList();
        return persist(tournament, phase, null, planned);
    }

    /**
     * Persists planned matches ({@code group} null outside a group), each kicking off at
     * {@value #KICK_OFF_HOUR}:00 of its day, and hands out the venues of the tournament and the
     * ACTIVE referees by cycling both lists. A venue or referee already busy at that kick-off (in a
     * non-cancelled match of the tournament, including the ones created here) is skipped; when every
     * one is busy the field stays empty for the organizer to fill in, never double-booked.
     *
     * @throws BusinessRuleException when a planned day falls after the year 2100 (nothing is saved)
     */
    List<Match> persist(Tournament tournament, MatchPhase phase, TournamentGroup group, List<PlannedMatch> planned) {
        if (planned.stream().anyMatch(plan -> !fitsCalendar(plan.day()))) {
            throw new BusinessRuleException(NO_DATES_LEFT);
        }
        List<Venue> availableVenues = tournament.getVenues();
        List<AppUser> availableReferees = refereeService.activeReferees();
        Map<Long, Team> teamCache = new HashMap<>();
        Map<Instant, Set<Long>> busyVenues = new HashMap<>();
        Map<Instant, Set<Long>> busyReferees = new HashMap<>();
        for (Match existing : matches.findByTournamentIdOrderByScheduledAtAscIdAsc(tournament.getId())) {
            book(existing, busyVenues, busyReferees);
        }

        List<Match> created = new ArrayList<>(planned.size());
        int index = 0;
        for (PlannedMatch plan : planned) {
            Instant kickOff = kickOff(plan.day());
            Match match = Match.builder()
                    .tournament(tournament)
                    .phase(phase)
                    .group(group)
                    .roundNumber(plan.roundNumber())
                    .homeTeam(teamCache.computeIfAbsent(plan.homeTeamId(), teamService::requireTeam))
                    .awayTeam(teamCache.computeIfAbsent(plan.awayTeamId(), teamService::requireTeam))
                    .venue(firstFree(availableVenues, index, Venue::getId,
                            busyVenues.getOrDefault(kickOff, Set.of())))
                    .referee(firstFree(availableReferees, index, AppUser::getId,
                            busyReferees.getOrDefault(kickOff, Set.of())))
                    .scheduledAt(kickOff)
                    .status(MatchStatus.SCHEDULED)
                    .build();
            Match saved = matches.save(match);
            book(saved, busyVenues, busyReferees);
            created.add(saved);
            index++;
        }
        return created;
    }

    /** Marks the venue and referee of a non-cancelled match as busy at its kick-off. */
    private static void book(Match match, Map<Instant, Set<Long>> busyVenues, Map<Instant, Set<Long>> busyReferees) {
        if (match.getScheduledAt() == null || match.getStatus() == MatchStatus.CANCELLED) {
            return;
        }
        if (match.getVenue() != null) {
            busyVenues.computeIfAbsent(match.getScheduledAt(), key -> new HashSet<>()).add(match.getVenue().getId());
        }
        if (match.getReferee() != null) {
            busyReferees.computeIfAbsent(match.getScheduledAt(), key -> new HashSet<>()).add(match.getReferee().getId());
        }
    }

    /**
     * Cycles {@code candidates} from position {@code index} and returns the first one whose id is
     * not in {@code busy}; null when the list is empty or every candidate is busy.
     */
    private static <T> T firstFree(List<T> candidates, int index, Function<T, Long> idOf, Set<Long> busy) {
        for (int offset = 0; offset < candidates.size(); offset++) {
            T candidate = candidates.get((index + offset) % candidates.size());
            if (!busy.contains(idOf.apply(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * First matchday that can still be played: the preferred day, or the earliest later day whose
     * kick-off is still in the future.
     *
     * <p>Generating the fixture list after {@value #KICK_OFF_HOUR}:00 would otherwise place the
     * first round in the past, where captains can no longer submit a lineup for it until the
     * organizer moves every match by hand.
     */
    LocalDate firstAvailableMatchDay(LocalDate preferred) {
        LocalDate today = LocalDate.now(clock);
        LocalDate earliest = kickOff(today).isAfter(clock.instant()) ? today : today.plusDays(1);
        return preferred.isAfter(earliest) ? preferred : earliest;
    }

    Instant kickOff(LocalDate day) {
        return day.atTime(LocalTime.of(KICK_OFF_HOUR, 0)).atZone(clock.getZone()).toInstant();
    }

    /** The calendar day, in the application zone, of the latest kick-off among {@code all}. */
    Optional<LocalDate> lastScheduledDate(List<Match> all) {
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
            requireKickOffInRange(request.scheduledAt());
            match.setScheduledAt(request.scheduledAt());
            changes.put("scheduledAt", request.scheduledAt().toString());
        }
        if (request.venueId() != null) {
            Venue venue = requireVenueOf(match.getTournament(), request.venueId());
            match.setVenue(venue);
            changes.put("venueId", venue.getId());
        }
        if (request.refereeId() != null) {
            AppUser referee = requireActiveReferee(request.refereeId());
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

    /** 400 unless the kick-off lies in the years 2000 to 2100. */
    static void requireKickOffInRange(Instant scheduledAt) {
        if (scheduledAt.isBefore(EARLIEST_KICK_OFF) || !scheduledAt.isBefore(LATEST_KICK_OFF)) {
            throw new InvalidRequestException("La fecha del partido debe estar entre los años 2000 y 2100.");
        }
    }

    /** A venue registered for this tournament (409 otherwise). */
    static Venue requireVenueOf(Tournament tournament, Long venueId) {
        return tournament.getVenues().stream()
                .filter(candidate -> candidate.getId().equals(venueId))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("La cancha " + venueId + " no pertenece a este torneo."));
    }

    /** An ACTIVE user holding the REFEREE role (409 otherwise). */
    AppUser requireActiveReferee(Long refereeId) {
        AppUser referee = userService.getUser(refereeId);
        if (!referee.hasRole(Role.REFEREE)) {
            throw new BusinessRuleException("El usuario " + refereeId + " no es árbitro.");
        }
        if (!referee.isActive()) {
            throw new BusinessRuleException("El árbitro " + referee.getFullName()
                    + " está inactivo y no puede ser asignado.");
        }
        return referee;
    }

    /**
     * The two teams may meet in a match of {@code group}: both belong to it, they differ, and no
     * other match of the group ({@code ignoredMatchId} aside, whatever its status) is already
     * between them, in either orientation.
     */
    void requireGroupPairing(TournamentGroup group, Team home, Team away, Long ignoredMatchId) {
        if (home.getId().equals(away.getId())) {
            throw new BusinessRuleException("Un equipo no puede jugar contra sí mismo: elija dos equipos distintos.");
        }
        List<Long> members = groupTeams.findTeamIdsByGroupId(group.getId());
        for (Team team : List.of(home, away)) {
            if (!members.contains(team.getId())) {
                throw new BusinessRuleException("El equipo '" + team.getName() + "' no pertenece al "
                        + group.getName() + ".");
            }
        }
        matches.findByGroupIdOrderByIdAsc(group.getId()).stream()
                .filter(other -> !other.getId().equals(ignoredMatchId))
                .filter(other -> other.isBetween(home.getId(), away.getId()))
                .findFirst()
                .ifPresent(other -> {
                    throw new BusinessRuleException("En el " + group.getName() + " ya hay un partido entre '"
                            + home.getName() + "' y '" + away.getName() + "' (partido " + other.getId()
                            + "). Modifique o reabra ese partido en lugar de crear otro.");
                });
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
        for (Team team : List.of(newHome, newAway)) {
            if (!approved.contains(team.getId())) {
                throw new BusinessRuleException("El equipo '" + team.getName()
                        + "' no tiene una inscripción aprobada en este torneo.");
            }
        }
        if (match.getGroup() != null) {
            // Matchdays of a group are not fixed rounds (its matches are created one by one), so the
            // rule there is the pairing itself: both teams of the group, and one match per pairing.
            requireGroupPairing(match.getGroup(), newHome, newAway, match.getId());
        } else {
            List<Match> samePhase = matches.findByTournamentIdAndPhaseOrderByIdAsc(
                    match.getTournament().getId(), match.getPhase());
            for (Team team : List.of(newHome, newAway)) {
                if (!match.involves(team.getId())) {
                    requireNoOtherMatchInSlot(match, team, samePhase);
                }
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
        if (walkoverWinner != null) {
            advanceIfPhaseComplete(actor, match);
        }
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
        // Penalties only mean something for a knockout match that ended level; anywhere else
        // they are dropped so they can never contradict the score.
        boolean shootout = match.getPhase().isKnockout() && homeScore == awayScore;
        match.setHomePenalties(shootout ? request.homePenalties() : null);
        match.setAwayPenalties(shootout ? request.awayPenalties() : null);
        match.clearEvents();
        events.forEach(match::addEvent);

        auditService.record(actor.id(),
                correction ? AuditAction.MATCH_RESULT_CORRECTED : AuditAction.MATCH_RESULT_RECORDED,
                ENTITY_TYPE, matchId,
                Map.of("homeScore", homeScore, "awayScore", awayScore, "events", events.size()));
        bracketFix.ifPresent(fix -> applyPropagation(actor, match, fix));
        if (match.getPhase().isKnockout()) {
            advanceIfPhaseComplete(actor, match);
        }
        return assembler.toResponse(match);
    }

    /**
     * PLAYED or CANCELLED &rarr; SCHEDULED, clearing score, penalties, events, cancel reason and
     * walkover winner, so the organizer can fix the teams or record the match again. Later rounds
     * are left alone: if the winner already went further, only the same winner is accepted back
     * (see {@link #planPropagation(Match, Team)}). A group match is refused once the knockout exists:
     * left SCHEDULED it would block finishing and undoing, while its result can be corrected anyway.
     */
    @Transactional
    public MatchResponse reopen(AuthenticatedUser actor, Long matchId) {
        Match match = requireMatchLockingTournament(matchId);
        requireInProgress(match, "reabrir");
        if (!match.isReopenable()) {
            throw new BusinessRuleException("Solo se puede reabrir un partido jugado o cancelado; su estado actual es «"
                    + match.getStatus().label() + "».");
        }
        // A SCHEDULED group match blocks finishing the tournament and undoing the knockout, while
        // a result can still be corrected without reopening: a reopen here only strands the tournament.
        if (match.getPhase() == MatchPhase.GROUP
                && matches.existsByTournamentIdAndPhaseNot(match.getTournament().getId(), MatchPhase.GROUP)) {
            throw new BusinessRuleException("La fase de grupos ya terminó: deshaga la eliminatoria antes de reabrir "
                    + "partidos de grupo.");
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
        requireNotAdvancedBeyond(match.getTournament().getId(), nextPhase, loser);
        return Optional.of(new BracketFix(target, loser, winner));
    }

    /**
     * The team about to be replaced in {@code phase} must not stand in any later knockout phase
     * (it reached it from {@code phase} before that match was reopened): replacing it only in
     * {@code phase} would leave it in a round it no longer earned, possibly as champion.
     */
    private void requireNotAdvancedBeyond(Long tournamentId, MatchPhase phase, Team team) {
        for (MatchPhase later = phase.nextKnockoutPhase(); later != null; later = later.nextKnockoutPhase()) {
            boolean present = matches.findByTournamentIdAndPhaseOrderByIdAsc(tournamentId, later).stream()
                    .anyMatch(other -> other.involves(team.getId()) || isWalkoverWinner(other, team.getId()));
            if (present) {
                throw new BusinessRuleException("El equipo " + team.getName() + " ya avanzó a la " + later.label()
                        + ": deshaga o corrija primero esa fase.");
            }
        }
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
    /** Package-visible so {@link LineupService} serialises lineup saves with team changes. */
    Match requireMatchLockingTournament(Long matchId) {
        Long tournamentId = matches.findTournamentIdById(matchId)
                .orElseThrow(() -> NotFoundException.of("el partido", matchId));
        tournamentService.requireTournamentForUpdate(tournamentId);
        return requireMatch(matchId);
    }

    /** @param verb the Spanish infinitive phrase of what is being attempted, e.g. {@code "cancelar"} */
    static void requireInProgress(Match match, String verb) {
        match.getTournament().requireNotSuspended(verb + " un partido");
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
            throw new BusinessRuleException(goalMismatch("local", home.getName(), homeScore, homeGoals));
        }
        if (awayGoals != awayScore) {
            throw new BusinessRuleException(goalMismatch("visitante", away.getName(), awayScore, awayGoals));
        }
        return events;
    }

    /**
     * The reported score of a team does not match the goal events sent with it. Every goal needs
     * its scorer, so the message says whether scorers are missing or left over, and what to do.
     * Both quantities agree in number with their own noun, which is why it is composed here.
     *
     * @param side {@code "local"} or {@code "visitante"}
     */
    static String goalMismatch(String side, String teamName, int reportedScore, int goalEvents) {
        String facts = "el marcador indica " + Messages.plural(reportedScore, "gol", "goles") + " y hay "
                + Messages.plural(goalEvents, "registrado", "registrados");
        if (goalEvents < reportedScore) {
            return "Faltan goleadores del equipo " + side + " '" + teamName + "': " + facts
                    + ". Registre qué jugador marcó cada gol.";
        }
        return "Sobran goleadores del equipo " + side + " '" + teamName + "': " + facts
                + ". Quite los goles de más o corrija el marcador.";
    }
}
