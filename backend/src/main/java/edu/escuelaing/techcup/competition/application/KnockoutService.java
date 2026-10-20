package edu.escuelaing.techcup.competition.application;

import edu.escuelaing.techcup.competition.api.dto.CreateKnockoutRequest;
import edu.escuelaing.techcup.competition.api.dto.KnockoutSuggestionResponse;
import edu.escuelaing.techcup.competition.api.dto.MatchResponse;
import edu.escuelaing.techcup.competition.domain.KnockoutSeeding;
import edu.escuelaing.techcup.competition.domain.Match;
import edu.escuelaing.techcup.competition.domain.MatchPhase;
import edu.escuelaing.techcup.competition.domain.MatchStatus;
import edu.escuelaing.techcup.competition.infrastructure.MatchRepository;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.InvalidRequestException;
import edu.escuelaing.techcup.shared.exception.Messages;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.teams.application.TeamService;
import edu.escuelaing.techcup.tournaments.application.TournamentService;
import edu.escuelaing.techcup.tournaments.domain.Tournament;
import edu.escuelaing.techcup.tournaments.domain.TournamentStatus;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The first knockout round, built by the organizer (ORGANIZER; ADMIN inherits):
 * <ul>
 *   <li><b>Suggestion</b> for 2, 4 or 8 teams ({@link KnockoutSeeding}): qualifiers from the group
 *       tables (winners against runners-up of other groups), or from the general table when the
 *       tournament has no groups. Read-only.</li>
 *   <li><b>Creation</b> of the pairings the organizer confirmed, possibly edited: 1, 2 or 4
 *       pairings (final, semi-finals, quarter-finals), every team distinct and APPROVED in the
 *       tournament, the tournament IN_PROGRESS and not suspended, no knockout match yet, and no
 *       group match still SCHEDULED. The matches keep the given (bracket) order; the later rounds
 *       are drawn automatically from their winners ({@code MatchService}), and undoing this round
 *       goes back to the group stage.</li>
 * </ul>
 * Audited as KNOCKOUT_CREATED on the tournament.
 */
@Service
public class KnockoutService {

    private final MatchRepository matches;
    private final TournamentService tournamentService;
    private final TeamService teamService;
    private final StandingsService standingsService;
    private final MatchService matchService;
    private final MatchResponseAssembler assembler;
    private final AuditService auditService;

    public KnockoutService(MatchRepository matches, TournamentService tournamentService, TeamService teamService,
                           StandingsService standingsService, MatchService matchService,
                           MatchResponseAssembler assembler, AuditService auditService) {
        this.matches = matches;
        this.tournamentService = tournamentService;
        this.teamService = teamService;
        this.standingsService = standingsService;
        this.matchService = matchService;
        this.assembler = assembler;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public KnockoutSuggestionResponse suggest(Long tournamentId, int size) {
        if (!KnockoutSeeding.SIZES.contains(size)) {
            throw new InvalidRequestException("El tamaño de la eliminatoria debe ser 2, 4 u 8 equipos.");
        }
        tournamentService.requireTournament(tournamentId);
        List<KnockoutSeeding.GroupStanding> groups = standingsService.groupTables(tournamentId).stream()
                .map(table -> new KnockoutSeeding.GroupStanding(table.groupId(), table.name(), table.rows()))
                .toList();
        KnockoutSeeding.Suggestion suggestion = KnockoutSeeding.suggest(size, groups,
                groups.stream().anyMatch(group -> !group.rows().isEmpty()) ? List.of()
                        : standingsService.rows(tournamentId));
        return new KnockoutSuggestionResponse(KnockoutSeeding.phaseFor(size), size, suggestion.source(),
                suggestion.pairings().stream()
                        .map(pairing -> new KnockoutSuggestionResponse.Pairing(seed(pairing.home()), seed(pairing.away())))
                        .toList());
    }

    @Transactional
    public List<MatchResponse> create(AuthenticatedUser actor, Long tournamentId, CreateKnockoutRequest request) {
        List<CreateKnockoutRequest.Pairing> pairings = request.pairings();
        int size = pairings.size() * 2;
        if (!KnockoutSeeding.SIZES.contains(size)) {
            throw new InvalidRequestException("Indique 1, 2 o 4 cruces (final, semifinales o cuartos de final); "
                    + "se recibieron " + pairings.size() + ".");
        }
        Set<Long> seen = new HashSet<>();
        for (CreateKnockoutRequest.Pairing pairing : pairings) {
            if (pairing.homeTeamId().equals(pairing.awayTeamId())) {
                throw new InvalidRequestException("Un equipo no puede jugar contra sí mismo: revise los cruces.");
            }
            for (Long teamId : List.of(pairing.homeTeamId(), pairing.awayTeamId())) {
                if (!seen.add(teamId)) {
                    throw new InvalidRequestException("El equipo '" + teamService.requireTeam(teamId).getName()
                            + "' aparece en más de un cruce.");
                }
            }
        }

        Tournament tournament = tournamentService.requireTournamentForUpdate(tournamentId);
        tournament.requireNotSuspended("armar la eliminatoria");
        if (tournament.getStatus() != TournamentStatus.IN_PROGRESS) {
            throw new BusinessRuleException("La eliminatoria solo se puede armar mientras el torneo esté en progreso; "
                    + "su estado actual es «" + tournament.getStatus().label() + "».");
        }
        List<Match> all = matches.findByTournamentIdOrderByScheduledAtAscIdAsc(tournamentId);
        all.stream()
                .map(Match::getPhase)
                .filter(MatchPhase::isKnockout)
                .min(Enum::compareTo)
                .ifPresent(first -> {
                    throw new BusinessRuleException("La eliminatoria ya fue armada (empieza en " + first.label()
                            + "). Deshaga sus fases para volver a armarla.");
                });
        long pending = all.stream()
                .filter(match -> match.getPhase() == MatchPhase.GROUP && match.getStatus() == MatchStatus.SCHEDULED)
                .count();
        if (pending > 0) {
            throw new BusinessRuleException("La fase de grupos todavía tiene "
                    + Messages.plural(pending, "partido", "partidos") + " por jugar o cancelar.");
        }
        List<Long> approved = tournamentService.approvedTeamIds(tournamentId);
        for (Long teamId : seen) {
            if (!approved.contains(teamId)) {
                throw new BusinessRuleException("El equipo '" + teamService.requireTeam(teamId).getName()
                        + "' no tiene una inscripción aprobada en este torneo.");
            }
        }

        MatchPhase phase = KnockoutSeeding.phaseFor(size);
        List<FixtureStrategy.Fixture> fixtures = new ArrayList<>(pairings.size());
        pairings.forEach(pairing -> fixtures.add(new FixtureStrategy.Fixture(1, pairing.homeTeamId(),
                pairing.awayTeamId())));
        int roundOffset = matches.findMaxRoundNumber(tournamentId);
        LocalDate lastDate = matchService.lastScheduledDate(all).orElse(tournament.getStartDate());
        List<Match> created = matchService.persist(tournament, phase, fixtures, roundOffset,
                matchService.firstAvailableMatchDay(lastDate.plusDays(1)));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tournamentId", tournamentId);
        details.put("phase", phase.name());
        details.put("matches", created.size());
        details.put("pairings", pairings.stream()
                .map(pairing -> pairing.homeTeamId() + "-" + pairing.awayTeamId())
                .collect(Collectors.joining(",")));
        auditService.record(actor.id(), AuditAction.KNOCKOUT_CREATED, MatchService.TOURNAMENT_ENTITY_TYPE,
                tournamentId, details);
        return assembler.toResponses(created);
    }

    private static KnockoutSuggestionResponse.Seed seed(KnockoutSeeding.Seed seed) {
        return new KnockoutSuggestionResponse.Seed(seed.teamId(), seed.teamName(), seed.label(), seed.groupId());
    }
}
