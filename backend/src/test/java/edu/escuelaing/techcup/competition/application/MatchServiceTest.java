package edu.escuelaing.techcup.competition.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.competition.api.dto.RecordResultRequest;
import edu.escuelaing.techcup.competition.api.dto.UpdateMatchRequest;
import edu.escuelaing.techcup.competition.domain.CancelReason;
import edu.escuelaing.techcup.competition.domain.EventType;
import edu.escuelaing.techcup.competition.domain.Match;
import edu.escuelaing.techcup.competition.domain.MatchPhase;
import edu.escuelaing.techcup.competition.domain.MatchStatus;
import edu.escuelaing.techcup.competition.domain.Standings;
import edu.escuelaing.techcup.competition.infrastructure.MatchRepository;
import edu.escuelaing.techcup.identity.application.RefereeService;
import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.identity.domain.Role;
import edu.escuelaing.techcup.identity.domain.UserStatus;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Result validation and correction, rescheduling guards, walkover cancellations and the seeding
 * decisions of {@code advance}. The draw algorithms themselves are covered by
 * {@link RoundRobinFixtureStrategyTest} and {@link KnockoutFixtureStrategyTest}.
 */
@ExtendWith(MockitoExtension.class)
class MatchServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final Instant NOW = TODAY.atStartOfDay(ZONE).toInstant();
    private static final AuthenticatedUser ORGANIZER =
            new AuthenticatedUser(7L, "organizer@escuelaing.edu.co", Set.of("ORGANIZER"));

    @Mock
    private MatchRepository matches;
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

    private MatchService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZONE);
        service = new MatchService(matches, tournamentService, teamService, userService, refereeService,
                standingsService, new RoundRobinFixtureStrategy(new FixtureShuffler(new java.util.Random(1L))),
                new KnockoutFixtureStrategy(), new MatchResponseAssembler(matches), auditService, clock);
    }

    // --- result validation --------------------------------------------------------------------

    @Test
    void goalEventsMustAddUpToTheHomeScore() {
        givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));

        assertThatThrownBy(() -> service.recordResult(ORGANIZER, 3L, new RecordResultRequest(
                2, 0, null, null, List.of(goal(10L, 101L)))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Tigers")
                .hasMessageContaining("2 goles para el equipo 'Tigers', pero se envió 1 evento de gol");
    }

    @Test
    void goalEventsMustAddUpToTheAwayScore() {
        givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));

        assertThatThrownBy(() -> service.recordResult(ORGANIZER, 3L, new RecordResultRequest(
                1, 1, null, null, List.of(goal(10L, 101L), goal(10L, 102L)))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("1 gol para el equipo 'Tigers', pero se enviaron 2 eventos de gol");
    }

    @Test
    void ascorerMustBelongToTheTeamCredited() {
        givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));

        assertThatThrownBy(() -> service.recordResult(ORGANIZER, 3L, new RecordResultRequest(
                1, 0, null, null, List.of(goal(10L, 201L)))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no es integrante del equipo 'Tigers'");
    }

    @Test
    void anEventMustBelongToOneOfTheTwoTeamsOfTheMatch() {
        givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));

        assertThatThrownBy(() -> service.recordResult(ORGANIZER, 3L, new RecordResultRequest(
                1, 0, null, null, List.of(goal(99L, 101L)))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no juega este partido");
    }

    @Test
    void avalidResultIsRecordedAndTheMatchBecomesPlayed() {
        Match match = givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));
        when(matches.findDistinctPhases(1L)).thenReturn(List.of(MatchPhase.GROUP));
        when(userService.getUser(101L)).thenReturn(player(101L));
        when(userService.getUser(201L)).thenReturn(player(201L));

        var response = service.recordResult(ORGANIZER, 3L, new RecordResultRequest(
                1, 1, null, null, List.of(goal(10L, 101L), goal(20L, 201L))));

        assertThat(match.getStatus()).isEqualTo(MatchStatus.PLAYED);
        assertThat(match.getHomeScore()).isEqualTo(1);
        assertThat(match.getAwayScore()).isEqualTo(1);
        assertThat(response.events()).hasSize(2);
        assertThat(response.resultEditable()).isTrue();
        verify(auditService).record(eq(7L), eq(AuditAction.MATCH_RESULT_RECORDED), anyString(), eq(3L), any());
    }

    @Test
    void aPlayedMatchInTheLatestPhaseCanBeCorrected() {
        Match match = match(MatchPhase.GROUP, MatchStatus.PLAYED);
        match.setHomeScore(2);
        match.setAwayScore(0);
        givenMatch(match);
        when(matches.findDistinctPhases(1L)).thenReturn(List.of(MatchPhase.GROUP));
        when(userService.getUser(201L)).thenReturn(player(201L));

        var response = service.recordResult(ORGANIZER, 3L, new RecordResultRequest(
                0, 1, null, null, List.of(goal(20L, 201L))));

        assertThat(match.getStatus()).isEqualTo(MatchStatus.PLAYED);
        assertThat(match.getHomeScore()).isZero();
        assertThat(match.getAwayScore()).isEqualTo(1);
        assertThat(response.events()).singleElement().extracting(event -> event.playerId()).isEqualTo(201L);
        verify(auditService).record(eq(7L), eq(AuditAction.MATCH_RESULT_CORRECTED), anyString(), eq(3L), any());
    }

    @Test
    void aPlayedMatchCannotBeCorrectedOnceALaterPhaseWasDrawnFromIt() {
        givenMatch(match(MatchPhase.GROUP, MatchStatus.PLAYED));
        when(matches.findDistinctPhases(1L)).thenReturn(List.of(MatchPhase.GROUP, MatchPhase.QUARTERFINAL));

        assertThatThrownBy(() -> service.recordResult(ORGANIZER, 3L,
                new RecordResultRequest(0, 0, null, null, List.of())))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("siguiente fase");
    }

    @Test
    void aCancelledMatchDoesNotAcceptAResult() {
        givenMatch(match(MatchPhase.GROUP, MatchStatus.CANCELLED));

        assertThatThrownBy(() -> service.recordResult(ORGANIZER, 3L,
                new RecordResultRequest(0, 0, null, null, List.of())))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("«cancelado»");
    }

    @Test
    void resultsRequireTheTournamentToBeInProgress() {
        Match match = match(MatchPhase.GROUP, MatchStatus.SCHEDULED);
        match.getTournament().setStatus(TournamentStatus.FINISHED);
        givenMatch(match);

        assertThatThrownBy(() -> service.recordResult(ORGANIZER, 3L,
                new RecordResultRequest(0, 0, null, null, List.of())))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("mientras el torneo esté en progreso");
    }

    @Test
    void agroupMatchMayEndLevelWithNoPenalties() {
        Match match = givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));

        service.recordResult(ORGANIZER, 3L, new RecordResultRequest(0, 0, null, null, List.of()));

        assertThat(match.getStatus()).isEqualTo(MatchStatus.PLAYED);
    }

    @Test
    void aknockoutDrawNeedsAPenaltyShootOut() {
        givenMatch(match(MatchPhase.SEMIFINAL, MatchStatus.SCHEDULED));

        assertThatThrownBy(() -> service.recordResult(ORGANIZER, 3L,
                new RecordResultRequest(1, 1, null, null, List.of())))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("tanda de penales");
    }

    @Test
    void aknockoutPenaltyShootOutMustHaveAwinner() {
        givenMatch(match(MatchPhase.FINAL, MatchStatus.SCHEDULED));

        assertThatThrownBy(() -> service.recordResult(ORGANIZER, 3L,
                new RecordResultRequest(0, 0, 3, 3, List.of())))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("debe tener un ganador");
    }

    @Test
    void adecisiveShootOutResolvesAknockoutDraw() {
        Match match = givenMatch(match(MatchPhase.FINAL, MatchStatus.SCHEDULED));

        service.recordResult(ORGANIZER, 3L, new RecordResultRequest(0, 0, 4, 3, List.of()));

        assertThat(match.getStatus()).isEqualTo(MatchStatus.PLAYED);
        assertThat(match.winner()).get().extracting(Team::getId).isEqualTo(10L);
    }

    // --- rescheduling -----------------------------------------------------------------------------

    @Test
    void amatchThatHasKickedOffCannotBeRescheduled() {
        Match match = match(MatchPhase.GROUP, MatchStatus.SCHEDULED);
        match.setScheduledAt(NOW.minusSeconds(60));
        givenMatch(match);

        assertThatThrownBy(() -> service.update(ORGANIZER, 3L,
                new UpdateMatchRequest(NOW.plusSeconds(3600), null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ya inició y no se puede reprogramar");
    }

    @Test
    void aplayedMatchCannotBeRescheduled() {
        givenMatch(match(MatchPhase.GROUP, MatchStatus.PLAYED));

        assertThatThrownBy(() -> service.update(ORGANIZER, 3L,
                new UpdateMatchRequest(NOW.plusSeconds(3600), null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Solo se puede reprogramar un partido programado");
    }

    @Test
    void aMatchCannotBeMovedIntoThePast() {
        givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));

        assertThatThrownBy(() -> service.update(ORGANIZER, 3L,
                new UpdateMatchRequest(NOW.minusSeconds(1), null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("posterior al momento actual");
    }

    @Test
    void anInactiveRefereeCannotBeAppointed() {
        givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));
        AppUser referee = AppUser.builder().id(9L).fullName("Ref").status(UserStatus.INACTIVE)
                .roles(new HashSet<>(Set.of(Role.REFEREE))).build();
        when(userService.getUser(9L)).thenReturn(referee);

        assertThatThrownBy(() -> service.update(ORGANIZER, 3L, new UpdateMatchRequest(null, null, 9L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("está inactivo");
    }

    @Test
    void anActiveRefereeIsAppointed() {
        Match match = givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));
        AppUser referee = AppUser.builder().id(9L).fullName("Ref").status(UserStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(Role.REFEREE))).build();
        when(userService.getUser(9L)).thenReturn(referee);

        service.update(ORGANIZER, 3L, new UpdateMatchRequest(null, null, 9L));

        assertThat(match.getReferee()).isSameAs(referee);
    }

    @Test
    void anEmptyPatchIsRefused() {
        givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));

        assertThatThrownBy(() -> service.update(ORGANIZER, 3L, new UpdateMatchRequest(null, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("No hay nada que actualizar");
    }

    // --- cancellation -----------------------------------------------------------------------------

    @Test
    void cancellingRequiresAreason() {
        assertThatThrownBy(() -> service.cancel(ORGANIZER, 3L, null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("motivo de la cancelación");
    }

    @Test
    void cancellingAGroupMatchKeepsItWithItsReasonAndNoWalkover() {
        Match match = givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));

        var response = service.cancel(ORGANIZER, 3L, CancelReason.NO_SHOW, 20L);

        assertThat(match.getStatus()).isEqualTo(MatchStatus.CANCELLED);
        assertThat(match.getCancelReason()).isEqualTo(CancelReason.NO_SHOW);
        assertThat(match.getWalkoverWinnerTeam()).isNull();
        assertThat(response.walkoverWinnerTeamId()).isNull();
        assertThat(response.resultEditable()).isFalse();
    }

    @Test
    void cancellingAKnockoutMatchNeedsTheTeamThatGoesThrough() {
        givenMatch(match(MatchPhase.SEMIFINAL, MatchStatus.SCHEDULED));

        assertThatThrownBy(() -> service.cancel(ORGANIZER, 3L, CancelReason.DISQUALIFIED, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("qué equipo avanza");
    }

    @Test
    void theWalkoverWinnerMustPlayTheMatch() {
        givenMatch(match(MatchPhase.SEMIFINAL, MatchStatus.SCHEDULED));

        assertThatThrownBy(() -> service.cancel(ORGANIZER, 3L, CancelReason.DISQUALIFIED, 99L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no juega este partido");
    }

    @Test
    void cancellingAKnockoutMatchRecordsTheWalkover() {
        Match match = givenMatch(match(MatchPhase.SEMIFINAL, MatchStatus.SCHEDULED));

        var response = service.cancel(ORGANIZER, 3L, CancelReason.NO_SHOW, 20L);

        assertThat(match.getStatus()).isEqualTo(MatchStatus.CANCELLED);
        assertThat(match.getWalkoverWinnerTeam().getId()).isEqualTo(20L);
        assertThat(response.walkoverWinnerTeamId()).isEqualTo(20L);
    }

    @Test
    void cancellationsRequireTheTournamentToBeInProgress() {
        Match match = match(MatchPhase.GROUP, MatchStatus.SCHEDULED);
        match.getTournament().setStatus(TournamentStatus.FINISHED);
        givenMatch(match);

        assertThatThrownBy(() -> service.cancel(ORGANIZER, 3L, CancelReason.NO_SHOW, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("mientras el torneo esté en progreso");
    }

    // --- generation and advance -----------------------------------------------------------------

    @Test
    void fixturesAreOnlyGeneratedForAnInProgressTournament() {
        when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(tournament(TournamentStatus.ACTIVE));

        assertThatThrownBy(() -> service.generate(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("mientras el torneo esté en progreso");
    }

    @Test
    void fixturesAreOnlyGeneratedOnce() {
        when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(tournament(TournamentStatus.IN_PROGRESS));
        when(matches.existsByTournamentId(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.generate(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ya fue generado");
    }

    @Test
    void generatingTheGroupStageNeedsTwoApprovedTeams() {
        when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(tournament(TournamentStatus.IN_PROGRESS));
        when(matches.existsByTournamentId(1L)).thenReturn(false);
        when(tournamentService.approvedTeamIds(1L)).thenReturn(List.of(10L));

        assertThatThrownBy(() -> service.generate(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("al menos 2 equipos aprobados");
    }

    @Test
    void advanceRefusesWhileTheCurrentPhaseIsUnfinished() {
        when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(tournament(TournamentStatus.IN_PROGRESS));
        when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L))
                .thenReturn(List.of(match(MatchPhase.GROUP, MatchStatus.SCHEDULED)));

        assertThatThrownBy(() -> service.advance(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("todavía tiene 1 partido por jugar o cancelar");
    }

    @Test
    void advanceRefusesWithNoFixtureAtAll() {
        when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(tournament(TournamentStatus.IN_PROGRESS));
        when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.advance(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Genere la fase de grupos");
    }

    @Test
    void eightTeamsAdvanceToTheQuarterFinalsSeededOneVersusEight() {
        givenFinishedGroupStage(8);

        var created = service.advance(ORGANIZER, 1L);

        assertThat(created).hasSize(4);
        assertThat(created).allSatisfy(match -> assertThat(match.phase()).isEqualTo(MatchPhase.QUARTERFINAL));
        assertThat(created).extracting(response -> response.homeTeam().id())
                .containsExactly(1L, 2L, 3L, 4L);
        assertThat(created).extracting(response -> response.awayTeam().id())
                .containsExactly(8L, 7L, 6L, 5L);
    }

    @Test
    void fiveTeamsAdvanceToTheSemiFinals() {
        givenFinishedGroupStage(5);

        var created = service.advance(ORGANIZER, 1L);

        assertThat(created).hasSize(2);
        assertThat(created).allSatisfy(match -> assertThat(match.phase()).isEqualTo(MatchPhase.SEMIFINAL));
        assertThat(created).extracting(response -> response.homeTeam().id()).containsExactly(1L, 2L);
        assertThat(created).extracting(response -> response.awayTeam().id()).containsExactly(4L, 3L);
    }

    @Test
    void threeTeamsGoStraightToTheFinal() {
        givenFinishedGroupStage(3);

        var created = service.advance(ORGANIZER, 1L);

        assertThat(created).hasSize(1);
        assertThat(created.get(0).phase()).isEqualTo(MatchPhase.FINAL);
        assertThat(created.get(0).homeTeam().id()).isEqualTo(1L);
        assertThat(created.get(0).awayTeam().id()).isEqualTo(2L);
    }

    @Test
    void aknockoutTieWithNoWinnerBlocksTheBracket() {
        Match drawn = match(MatchPhase.SEMIFINAL, MatchStatus.PLAYED);
        drawn.setHomeScore(1);
        drawn.setAwayScore(1);
        when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(tournament(TournamentStatus.IN_PROGRESS));
        when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L)).thenReturn(List.of(drawn));

        assertThatThrownBy(() -> service.advance(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("penales para decidir");
    }

    @Test
    void acancelledKnockoutMatchWithoutAWalkoverBlocksTheBracket() {
        Match cancelled = match(MatchPhase.SEMIFINAL, MatchStatus.CANCELLED);
        cancelled.setCancelReason(CancelReason.NO_SHOW);
        when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(tournament(TournamentStatus.IN_PROGRESS));
        when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L)).thenReturn(List.of(cancelled));

        assertThatThrownBy(() -> service.advance(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("sin indicar qué equipo avanza");
    }

    @Test
    void theWalkoverWinnerOfACancelledKnockoutMatchGoesThrough() {
        Match played = playedMatch(MatchPhase.SEMIFINAL, 1L, 4L, 2, 0);
        Match cancelled = match(MatchPhase.SEMIFINAL, MatchStatus.CANCELLED);
        cancelled.setCancelReason(CancelReason.DISQUALIFIED);
        cancelled.setWalkoverWinnerTeam(cancelled.getAwayTeam());
        when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(tournament(TournamentStatus.IN_PROGRESS));
        when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L)).thenReturn(List.of(played, cancelled));
        when(matches.findMaxRoundNumber(1L)).thenReturn(4);
        when(refereeService.activeReferees()).thenReturn(List.of());
        when(teamService.requireTeam(1L)).thenReturn(team(1L, "Team 1"));
        when(teamService.requireTeam(20L)).thenReturn(team(20L, "Lions"));
        when(matches.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var created = service.advance(ORGANIZER, 1L);

        assertThat(created).singleElement().satisfies(finalMatch -> {
            assertThat(finalMatch.phase()).isEqualTo(MatchPhase.FINAL);
            assertThat(finalMatch.homeTeam().id()).isEqualTo(1L);
            assertThat(finalMatch.awayTeam().id()).isEqualTo(20L);
        });
    }

    @Test
    void theBracketStopsAfterTheFinal() {
        when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(tournament(TournamentStatus.IN_PROGRESS));
        when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L))
                .thenReturn(List.of(playedMatch(MatchPhase.FINAL, 10L, 20L, 2, 0)));

        assertThatThrownBy(() -> service.advance(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("La final ya se jugó");
    }

    // --- helpers ---------------------------------------------------------------------------------

    private void givenFinishedGroupStage(int teamCount) {
        when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(tournament(TournamentStatus.IN_PROGRESS));
        when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L))
                .thenReturn(List.of(playedMatch(MatchPhase.GROUP, 1L, 2L, 1, 0)));
        List<Standings.Row> table = new java.util.ArrayList<>();
        for (int seed = 1; seed <= teamCount; seed++) {
            table.add(new Standings.Row(seed, (long) seed, "Team " + seed, 1, 1, 0, 0, 1, 0, 1,
                    3 * (teamCount - seed + 1)));
        }
        when(standingsService.rows(1L)).thenReturn(table);
        when(matches.findMaxRoundNumber(1L)).thenReturn(3);
        when(refereeService.activeReferees()).thenReturn(List.of());
        for (int seed = 1; seed <= teamCount; seed++) {
            lenient().when(teamService.requireTeam((long) seed)).thenReturn(team((long) seed, "Team " + seed));
        }
        when(matches.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Match givenMatch(Match match) {
        when(matches.findById(3L)).thenReturn(Optional.of(match));
        return match;
    }

    private static RecordResultRequest.Event goal(Long teamId, Long playerId) {
        return new RecordResultRequest.Event(teamId, playerId, EventType.GOAL, 10);
    }

    private static Match match(MatchPhase phase, MatchStatus status) {
        return Match.builder()
                .id(3L)
                .tournament(tournament(TournamentStatus.IN_PROGRESS))
                .phase(phase)
                .roundNumber(1)
                .homeTeam(teamWithMembers(10L, "Tigers", 101L, 102L))
                .awayTeam(teamWithMembers(20L, "Lions", 201L, 202L))
                .scheduledAt(NOW.plusSeconds(7200))
                .status(status)
                .build();
    }

    private static Match playedMatch(MatchPhase phase, Long homeTeamId, Long awayTeamId, int home, int away) {
        return Match.builder()
                .id(50L + phase.ordinal())
                .tournament(tournament(TournamentStatus.IN_PROGRESS))
                .phase(phase)
                .roundNumber(1)
                .homeTeam(team(homeTeamId, "Team " + homeTeamId))
                .awayTeam(team(awayTeamId, "Team " + awayTeamId))
                .scheduledAt(NOW.minusSeconds(7200))
                .status(MatchStatus.PLAYED)
                .homeScore(home)
                .awayScore(away)
                .build();
    }

    private static Tournament tournament(TournamentStatus status) {
        return Tournament.builder()
                .id(1L)
                .name("TechCup 2026-1")
                .startDate(TODAY)
                .endDate(TODAY.plusDays(30))
                .registrationDeadline(TODAY.minusDays(5))
                .maxTeams(8)
                .fee(BigDecimal.TEN)
                .status(status)
                .build();
    }

    private static Team team(Long id, String name) {
        return Team.builder().id(id).name(name).colors("blue").captain(player(id * 100))
                .status(TeamStatus.ACTIVE).build();
    }

    private static Team teamWithMembers(Long id, String name, Long... memberIds) {
        Team team = Team.builder().id(id).name(name).colors("blue").captain(player(memberIds[0]))
                .status(TeamStatus.ACTIVE).build();
        for (Long memberId : memberIds) {
            team.addMember(player(memberId));
        }
        return team;
    }

    private static AppUser player(Long id) {
        return AppUser.builder().id(id).fullName("Player " + id).build();
    }
}
