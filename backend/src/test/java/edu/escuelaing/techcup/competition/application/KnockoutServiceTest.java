package edu.escuelaing.techcup.competition.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.competition.api.dto.CreateKnockoutRequest;
import edu.escuelaing.techcup.competition.domain.Match;
import edu.escuelaing.techcup.competition.domain.MatchPhase;
import edu.escuelaing.techcup.competition.domain.MatchStatus;
import edu.escuelaing.techcup.competition.domain.Standings;
import edu.escuelaing.techcup.competition.infrastructure.GroupTeamRepository;
import edu.escuelaing.techcup.competition.infrastructure.LineupRepository;
import edu.escuelaing.techcup.competition.infrastructure.MatchRepository;
import edu.escuelaing.techcup.identity.application.RefereeService;
import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.InvalidRequestException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.teams.application.TeamService;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.teams.domain.TeamStatus;
import edu.escuelaing.techcup.tournaments.application.TournamentService;
import edu.escuelaing.techcup.tournaments.domain.Tournament;
import edu.escuelaing.techcup.tournaments.domain.TournamentStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The knockout suggestion and the creation of the first knockout round from the pairings the
 * organizer confirmed. The pairing algorithm itself is covered by {@code KnockoutSeedingTest}; the
 * later rounds, drawn automatically from these matches, by {@link MatchServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class KnockoutServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final Instant NOW = TODAY.atStartOfDay(ZONE).toInstant();
    private static final AuthenticatedUser ORGANIZER =
            new AuthenticatedUser(7L, "organizer@escuelaing.edu.co", Set.of("ORGANIZER"));

    @Mock
    private MatchRepository matches;
    @Mock
    private LineupRepository lineups;
    @Mock
    private GroupTeamRepository groupTeams;
    @Mock
    private TournamentService tournamentService;
    @Mock
    private TeamService teamService;
    @Mock
    private UserService userService;
    @Mock
    private RefereeService refereeService;
    @Mock
    private StandingsService standingsService;
    @Mock
    private AuditService auditService;

    private Tournament tournament;
    private KnockoutService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZONE);
        MatchResponseAssembler assembler = new MatchResponseAssembler();
        MatchService matchService = new MatchService(matches, lineups, groupTeams, tournamentService, teamService,
                userService, refereeService, new KnockoutFixtureStrategy(), assembler, auditService, clock);
        service = new KnockoutService(matches, tournamentService, teamService, standingsService, matchService,
                assembler, auditService);
        tournament = Tournament.builder().id(1L).name("TechCup 2026-1").startDate(TODAY).endDate(TODAY.plusDays(30))
                .registrationDeadline(TODAY.minusDays(5)).maxTeams(8).fee(BigDecimal.TEN)
                .status(TournamentStatus.IN_PROGRESS).build();
        lenient().when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(tournament);
        for (long id = 1; id <= 9; id++) {
            lenient().when(teamService.requireTeam(id)).thenReturn(team(id));
        }
    }

    // --- suggestion -------------------------------------------------------------------------------

    @Test
    void theSuggestionCrossesGroupWinnersWithRunnersUpOfOtherGroups() {
        when(standingsService.groupTables(1L)).thenReturn(List.of(
                new StandingsService.GroupTable(5L, "Grupo A", List.of(row(1, 1L, 9), row(2, 2L, 6), row(3, 3L, 3), row(4, 4L, 0))),
                new StandingsService.GroupTable(6L, "Grupo B", List.of(row(1, 5L, 6), row(2, 6L, 3), row(3, 7L, 0)))));

        var suggestion = service.suggest(1L, 4);

        assertThat(suggestion.phase()).isEqualTo(MatchPhase.SEMIFINAL);
        assertThat(suggestion.source()).isEqualTo("GROUPS");
        assertThat(suggestion.pairings()).extracting(pairing -> pairing.home().teamId()).containsExactly(1L, 5L);
        assertThat(suggestion.pairings()).extracting(pairing -> pairing.away().teamId()).containsExactly(6L, 2L);
        assertThat(suggestion.pairings()).allSatisfy(pairing ->
                assertThat(pairing.home().groupId()).isNotEqualTo(pairing.away().groupId()));
        verify(standingsService, never()).rows(any());
    }

    @Test
    void withoutGroupsTheSuggestionSeedsTheGeneralTable() {
        when(standingsService.groupTables(1L)).thenReturn(List.of());
        when(standingsService.rows(1L)).thenReturn(List.of(row(1, 3L, 9), row(2, 1L, 6), row(3, 2L, 3)));

        var suggestion = service.suggest(1L, 2);

        assertThat(suggestion.phase()).isEqualTo(MatchPhase.FINAL);
        assertThat(suggestion.source()).isEqualTo("GENERAL");
        assertThat(suggestion.pairings()).singleElement().satisfies(pairing -> {
            assertThat(pairing.home().teamId()).isEqualTo(3L);
            assertThat(pairing.away().teamId()).isEqualTo(1L);
        });
    }

    @Test
    void theSuggestionOnlyKnowsSizesTwoFourAndEight() {
        assertThatThrownBy(() -> service.suggest(1L, 6))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("El tamaño de la eliminatoria debe ser 2, 4 u 8 equipos.");
        verifyNoInteractions(standingsService);
    }

    // --- creation ---------------------------------------------------------------------------------

    @Test
    void theConfirmedPairingsBecomeTheFirstRoundInTheirOrder() {
        givenFinishedGroupStage();
        when(tournamentService.approvedTeamIds(1L)).thenReturn(List.of(1L, 2L, 3L, 4L, 5L));
        when(refereeService.activeReferees()).thenReturn(List.of());
        when(matches.findMaxRoundNumber(1L)).thenReturn(3);
        List<Match> saved = new ArrayList<>();
        when(matches.save(any())).thenAnswer(invocation -> {
            saved.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });

        var created = service.create(ORGANIZER, 1L, request(4L, 1L, 2L, 3L));

        assertThat(created).hasSize(2);
        assertThat(saved).extracting(match -> match.getHomeTeam().getId()).containsExactly(4L, 2L);
        assertThat(saved).extracting(match -> match.getAwayTeam().getId()).containsExactly(1L, 3L);
        assertThat(saved).allSatisfy(match -> {
            assertThat(match.getPhase()).isEqualTo(MatchPhase.SEMIFINAL);
            assertThat(match.getGroup()).isNull();
            assertThat(match.getRoundNumber()).isEqualTo(4);
            assertThat(match.getStatus()).isEqualTo(MatchStatus.SCHEDULED);
        });
        verify(auditService).record(eq(7L), eq(AuditAction.KNOCKOUT_CREATED), eq("TOURNAMENT"), eq(1L),
                argThat(details -> "SEMIFINAL".equals(details.get("phase")) && "4-1,2-3".equals(details.get("pairings"))));
    }

    @Test
    void thePairingCountMustMakeAFinalSemiFinalsOrQuarterFinals() {
        assertThatThrownBy(() -> service.create(ORGANIZER, 1L, request(1L, 2L, 3L, 4L, 5L, 6L)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Indique 1, 2 o 4 cruces");
        verifyNoInteractions(matches);
    }

    @Test
    void aTeamAppearsInOnePairingOnly() {
        assertThatThrownBy(() -> service.create(ORGANIZER, 1L, request(1L, 2L, 2L, 3L)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("El equipo 'Team 2' aparece en más de un cruce.");
        assertThatThrownBy(() -> service.create(ORGANIZER, 1L, request(1L, 1L)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("no puede jugar contra sí mismo");
    }

    @Test
    void theKnockoutIsBuiltOnce() {
        when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L)).thenReturn(List.of(
                match(MatchPhase.GROUP, MatchStatus.PLAYED), match(MatchPhase.SEMIFINAL, MatchStatus.SCHEDULED)));

        assertThatThrownBy(() -> service.create(ORGANIZER, 1L, request(1L, 2L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("La eliminatoria ya fue armada (empieza en semifinal)");
        verify(matches, never()).save(any());
    }

    @Test
    void everyGroupMatchMustBePlayedOrCancelledFirst() {
        when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L)).thenReturn(List.of(
                match(MatchPhase.GROUP, MatchStatus.PLAYED), match(MatchPhase.GROUP, MatchStatus.SCHEDULED)));

        assertThatThrownBy(() -> service.create(ORGANIZER, 1L, request(1L, 2L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("La fase de grupos todavía tiene 1 partido por jugar o cancelar.");
    }

    @Test
    void everyTeamMustBeApprovedInTheTournament() {
        givenFinishedGroupStage();
        when(tournamentService.approvedTeamIds(1L)).thenReturn(List.of(1L));

        assertThatThrownBy(() -> service.create(ORGANIZER, 1L, request(1L, 9L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El equipo 'Team 9' no tiene una inscripción aprobada en este torneo.");
    }

    @Test
    void theKnockoutNeedsAnInProgressTournamentThatIsNotSuspended() {
        tournament.setStatus(TournamentStatus.FINISHED);
        assertThatThrownBy(() -> service.create(ORGANIZER, 1L, request(1L, 2L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("mientras el torneo esté en progreso");

        tournament.setStatus(TournamentStatus.IN_PROGRESS);
        tournament.suspend();
        assertThatThrownBy(() -> service.create(ORGANIZER, 1L, request(1L, 2L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("armar la eliminatoria");
        verifyNoInteractions(auditService);
    }

    // --- helpers ----------------------------------------------------------------------------------

    private void givenFinishedGroupStage() {
        when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L)).thenReturn(List.of(
                match(MatchPhase.GROUP, MatchStatus.PLAYED), match(MatchPhase.GROUP, MatchStatus.CANCELLED)));
    }

    private static CreateKnockoutRequest request(Long... teamIds) {
        List<CreateKnockoutRequest.Pairing> pairings = new ArrayList<>();
        for (int index = 0; index < teamIds.length; index += 2) {
            pairings.add(new CreateKnockoutRequest.Pairing(teamIds[index], teamIds[index + 1]));
        }
        return new CreateKnockoutRequest(pairings);
    }

    private Match match(MatchPhase phase, MatchStatus status) {
        return Match.builder().id((long) phase.ordinal() * 10 + status.ordinal()).tournament(tournament).phase(phase)
                .roundNumber(1).homeTeam(team(1L)).awayTeam(team(2L)).scheduledAt(NOW.minusSeconds(3600))
                .status(status).build();
    }

    private static Standings.Row row(int position, Long teamId, int points) {
        return new Standings.Row(position, teamId, "Team " + teamId, 2, 0, 0, 0, points, 0, points, points);
    }

    private static Team team(Long id) {
        return Team.builder().id(id).name("Team " + id).colors("blue")
                .captain(AppUser.builder().id(id * 100).fullName("Captain " + id).build())
                .status(TeamStatus.ACTIVE).build();
    }
}
