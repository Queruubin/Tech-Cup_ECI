package edu.escuelaing.techcup.competition.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.competition.api.dto.RecordResultRequest;
import edu.escuelaing.techcup.competition.api.dto.UpdateMatchRequest;
import edu.escuelaing.techcup.competition.domain.CancelReason;
import edu.escuelaing.techcup.competition.domain.EventType;
import edu.escuelaing.techcup.competition.domain.Match;
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
import edu.escuelaing.techcup.identity.domain.UserStatus;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.NotFoundException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.teams.application.TeamService;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.teams.domain.TeamStatus;
import edu.escuelaing.techcup.tournaments.application.TournamentService;
import edu.escuelaing.techcup.tournaments.domain.Tournament;
import edu.escuelaing.techcup.tournaments.domain.TournamentStatus;
import edu.escuelaing.techcup.tournaments.domain.Venue;
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
 * Result validation and correction, match corrections (schedule, referee, teams), knockout
 * propagation, reopening, walkover cancellations, undoing a phase, the knockout rounds drawn by
 * {@code advance} or automatically after the last result of a phase, and the visibility of the
 * matches of a suspended tournament. The draw algorithms themselves are covered by
 * {@link RoundRobinFixtureStrategyTest} and {@link KnockoutFixtureStrategyTest}; the group stage by
 * {@link GroupStageServiceTest} and the first knockout round by {@link KnockoutServiceTest}.
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
    private LineupRepository lineups;
    @Mock
    private TournamentService tournamentService;
    @Mock
    private TeamService teamService;
    @Mock
    private UserService userService;
    @Mock
    private RefereeService refereeService;
    @Mock
    private GroupTeamRepository groupTeams;
    @Mock
    private AuditService auditService;

    private MatchService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZONE);
        service = new MatchService(matches, lineups, groupTeams, tournamentService, teamService, userService,
                refereeService, new KnockoutFixtureStrategy(), new MatchResponseAssembler(), auditService, clock);
    }

    // --- result validation --------------------------------------------------------------------

    @Test
    void goalEventsMustAddUpToTheHomeScore() {
        givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));

        assertThatThrownBy(() -> service.recordResult(ORGANIZER, 3L, new RecordResultRequest(
                2, 0, null, null, List.of(goal(10L, 101L)))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Tigers")
                .hasMessageContaining("Faltan goleadores del equipo local 'Tigers': el marcador indica 2 goles y hay 1 registrado.")
                .hasMessageContaining("Registre qué jugador marcó cada gol");
    }

    @Test
    void goalEventsMustAddUpToTheAwayScore() {
        givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));

        assertThatThrownBy(() -> service.recordResult(ORGANIZER, 3L, new RecordResultRequest(
                1, 1, null, null, List.of(goal(10L, 101L), goal(10L, 102L)))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Sobran goleadores del equipo local 'Tigers': el marcador indica 1 gol y hay 2 registrados.");
    }

    @Test
    void aScoreWithoutScorersAsksForThemOnTheAwaySide() {
        givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));

        assertThatThrownBy(() -> service.recordResult(ORGANIZER, 3L, new RecordResultRequest(
                0, 1, null, null, List.of())))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Faltan goleadores del equipo visitante")
                .hasMessageContaining("el marcador indica 1 gol y hay 0 registrados.");
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
        when(userService.getUser(101L)).thenReturn(player(101L));
        when(userService.getUser(201L)).thenReturn(player(201L));

        var response = service.recordResult(ORGANIZER, 3L, new RecordResultRequest(
                1, 1, null, null, List.of(goal(10L, 101L), goal(20L, 201L))));

        assertThat(match.getStatus()).isEqualTo(MatchStatus.PLAYED);
        assertThat(match.getHomeScore()).isEqualTo(1);
        assertThat(match.getAwayScore()).isEqualTo(1);
        assertThat(response.events()).hasSize(2);
        assertThat(response.resultEditable()).isTrue();
        assertThat(response.teamsEditable()).isFalse();
        assertThat(response.reopenable()).isTrue();
        verify(auditService).record(eq(7L), eq(AuditAction.MATCH_RESULT_RECORDED), anyString(), eq(3L), any());
    }

    @Test
    void aPlayedMatchCanBeCorrectedEvenAfterLaterPhasesWereDrawn() {
        Match match = match(MatchPhase.GROUP, MatchStatus.PLAYED);
        match.setHomeScore(2);
        match.setAwayScore(0);
        givenMatch(match);
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
    void aCancelledMatchDoesNotAcceptAResult() {
        givenMatch(match(MatchPhase.GROUP, MatchStatus.CANCELLED));

        assertThatThrownBy(() -> service.recordResult(ORGANIZER, 3L,
                new RecordResultRequest(0, 0, null, null, List.of())))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("«cancelado»")
                .hasMessageContaining("reábralo primero");
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
    void penaltiesAreDroppedOutsideAknockoutDraw() {
        Match group = givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));

        service.recordResult(ORGANIZER, 3L, new RecordResultRequest(0, 0, 5, 4, List.of()));

        assertThat(group.getHomePenalties()).isNull();
        assertThat(group.getAwayPenalties()).isNull();
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

    // --- match correction (PATCH) -----------------------------------------------------------------

    @Test
    void aMatchThatHasKickedOffCanStillBeCorrected() {
        Match match = match(MatchPhase.GROUP, MatchStatus.SCHEDULED);
        match.setScheduledAt(NOW.minusSeconds(60));
        givenMatch(match);

        service.update(ORGANIZER, 3L, new UpdateMatchRequest(NOW.plusSeconds(3600), null, null, null, null));

        assertThat(match.getScheduledAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    void aPlayedMatchMayBeMovedToWhenItWasActuallyPlayed() {
        Match match = givenMatch(match(MatchPhase.GROUP, MatchStatus.PLAYED));

        service.update(ORGANIZER, 3L, new UpdateMatchRequest(NOW.minusSeconds(86_400), null, null, null, null));

        assertThat(match.getScheduledAt()).isEqualTo(NOW.minusSeconds(86_400));
        verify(auditService).record(eq(7L), eq(AuditAction.MATCH_UPDATED), eq("MATCH"), eq(3L), any());
    }

    @Test
    void matchCorrectionsRequireTheTournamentToBeInProgress() {
        Match match = match(MatchPhase.GROUP, MatchStatus.PLAYED);
        match.getTournament().setStatus(TournamentStatus.FINISHED);
        givenMatch(match);

        assertThatThrownBy(() -> service.update(ORGANIZER, 3L,
                new UpdateMatchRequest(NOW.plusSeconds(3600), null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("mientras el torneo esté en progreso");
    }

    @Test
    void anInactiveRefereeCannotBeAppointed() {
        givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));
        AppUser referee = AppUser.builder().id(9L).fullName("Ref").status(UserStatus.INACTIVE)
                .roles(new HashSet<>(Set.of(Role.REFEREE))).build();
        when(userService.getUser(9L)).thenReturn(referee);

        assertThatThrownBy(() -> service.update(ORGANIZER, 3L, new UpdateMatchRequest(null, null, 9L, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("está inactivo");
    }

    @Test
    void anActiveRefereeIsAppointed() {
        Match match = givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));
        AppUser referee = AppUser.builder().id(9L).fullName("Ref").status(UserStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(Role.REFEREE))).build();
        when(userService.getUser(9L)).thenReturn(referee);

        service.update(ORGANIZER, 3L, new UpdateMatchRequest(null, null, 9L, null, null));

        assertThat(match.getReferee()).isSameAs(referee);
    }

    @Test
    void anEmptyPatchIsRefused() {
        givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));

        assertThatThrownBy(() -> service.update(ORGANIZER, 3L, new UpdateMatchRequest(null, null, null, null, null)))
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
        assertThat(response.teamsEditable()).isTrue();
        assertThat(response.reopenable()).isTrue();
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

    // --- advance and automatic knockout rounds ---------------------------------------------------

    @Test
    void advanceNeverLeavesTheGroupStage() {
        when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(tournament(TournamentStatus.IN_PROGRESS));
        when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L))
                .thenReturn(List.of(playedMatch(MatchPhase.GROUP, 1L, 2L, 1, 0)));

        assertThatThrownBy(() -> service.advance(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Armar eliminatoria");
        verify(matches, never()).save(any());
    }

    @Test
    void advanceRefusesWhileTheCurrentPhaseIsUnfinished() {
        when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(tournament(TournamentStatus.IN_PROGRESS));
        when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L))
                .thenReturn(List.of(match(MatchPhase.SEMIFINAL, MatchStatus.SCHEDULED)));

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
                .hasMessageContaining("Armar eliminatoria");
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
        cancelled.setId(played.getId() + 1);
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

    /**
     * QF1 (1v8) is moved to the last kick-off: the semi-finals must still pair the winners by
     * bracket slot (W1-W4, W2-W3), not by kick-off order.
     */
    @Test
    void reschedulingAQuarterFinalDoesNotChangeTheSemiFinalPairing() {
        Match qf1 = playedMatch(MatchPhase.QUARTERFINAL, 1L, 8L, 1, 0);
        qf1.setId(11L);
        qf1.setScheduledAt(NOW.plusSeconds(86_400));
        Match qf2 = playedMatch(MatchPhase.QUARTERFINAL, 2L, 7L, 1, 0);
        qf2.setId(12L);
        Match qf3 = playedMatch(MatchPhase.QUARTERFINAL, 3L, 6L, 1, 0);
        qf3.setId(13L);
        Match qf4 = playedMatch(MatchPhase.QUARTERFINAL, 4L, 5L, 1, 0);
        qf4.setId(14L);
        when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(tournament(TournamentStatus.IN_PROGRESS));
        when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L)).thenReturn(List.of(
                playedMatch(MatchPhase.GROUP, 1L, 2L, 1, 0), qf2, qf3, qf4, qf1));
        when(matches.findMaxRoundNumber(1L)).thenReturn(4);
        when(refereeService.activeReferees()).thenReturn(List.of());
        for (long seed = 1; seed <= 4; seed++) {
            when(teamService.requireTeam(seed)).thenReturn(team(seed, "Team " + seed));
        }
        when(matches.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var created = service.advance(ORGANIZER, 1L);

        assertThat(created).allSatisfy(match -> assertThat(match.phase()).isEqualTo(MatchPhase.SEMIFINAL));
        assertThat(created).extracting(response -> response.homeTeam().id()).containsExactly(1L, 2L);
        assertThat(created).extracting(response -> response.awayTeam().id()).containsExactly(4L, 3L);
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

    // --- team replacement ------------------------------------------------------------------------

    @Test
    void theTeamsOfAPlayedMatchCannotChangeUntilItIsReopened() {
        givenMatch(match(MatchPhase.GROUP, MatchStatus.PLAYED));

        assertThatThrownBy(() -> service.update(ORGANIZER, 3L, new UpdateMatchRequest(null, null, null, 30L, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Reabra el partido antes de cambiar los equipos: tiene un resultado registrado.");
        verifyNoInteractions(lineups, auditService);
    }

    @Test
    void aTeamCannotPlayItself() {
        givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));

        assertThatThrownBy(() -> service.update(ORGANIZER, 3L, new UpdateMatchRequest(null, null, null, 20L, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("contra sí mismo");
    }

    @Test
    void anEnteringTeamMustBeApprovedInTheTournament() {
        givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));
        when(teamService.requireTeam(30L)).thenReturn(team(30L, "Pumas"));
        when(tournamentService.approvedTeamIds(1L)).thenReturn(List.of(10L, 20L));

        assertThatThrownBy(() -> service.update(ORGANIZER, 3L, new UpdateMatchRequest(null, null, null, null, 30L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("'Pumas' no tiene una inscripción aprobada en este torneo");
    }

    @Test
    void anEnteringTeamCannotPlayTwiceInTheSameGroupRound() {
        Match match = givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));
        Match other = matchBetween(4L, MatchPhase.GROUP, MatchStatus.SCHEDULED, team(30L, "Pumas"), team(40L, "Bears"));
        other.setRoundNumber(1);
        when(teamService.requireTeam(30L)).thenReturn(other.getHomeTeam());
        when(tournamentService.approvedTeamIds(1L)).thenReturn(List.of(10L, 20L, 30L, 40L));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.GROUP)).thenReturn(List.of(match, other));

        assertThatThrownBy(() -> service.update(ORGANIZER, 3L, new UpdateMatchRequest(null, null, null, null, 30L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El equipo 'Pumas' ya juega otro partido en la jornada 1 de la fase de grupos.");
        assertThat(match.getAwayTeam().getId()).isEqualTo(20L);
    }

    @Test
    void anotherRoundOrACancelledMatchIsNoClash() {
        Match match = givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));
        Team pumas = team(30L, "Pumas");
        Match laterRound = matchBetween(4L, MatchPhase.GROUP, MatchStatus.SCHEDULED, pumas, team(40L, "Bears"));
        laterRound.setRoundNumber(2);
        Match cancelled = matchBetween(5L, MatchPhase.GROUP, MatchStatus.CANCELLED, pumas, team(50L, "Wolves"));
        cancelled.setRoundNumber(1);
        when(teamService.requireTeam(30L)).thenReturn(pumas);
        when(tournamentService.approvedTeamIds(1L)).thenReturn(List.of(10L, 20L, 30L, 40L, 50L));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.GROUP))
                .thenReturn(List.of(match, laterRound, cancelled));

        service.update(ORGANIZER, 3L, new UpdateMatchRequest(null, null, null, null, 30L));

        assertThat(match.getAwayTeam()).isSameAs(pumas);
    }

    @Test
    void anEnteringTeamCannotPlayTwiceInTheSameKnockoutPhase() {
        Match match = givenMatch(match(MatchPhase.SEMIFINAL, MatchStatus.SCHEDULED));
        Match other = matchBetween(4L, MatchPhase.SEMIFINAL, MatchStatus.SCHEDULED, team(30L, "Pumas"), team(40L, "Bears"));
        when(teamService.requireTeam(30L)).thenReturn(other.getHomeTeam());
        when(tournamentService.approvedTeamIds(1L)).thenReturn(List.of(10L, 20L, 30L, 40L));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.SEMIFINAL)).thenReturn(List.of(match, other));

        assertThatThrownBy(() -> service.update(ORGANIZER, 3L, new UpdateMatchRequest(null, null, null, 30L, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El equipo 'Pumas' ya juega otro partido de semifinal.");
    }

    @Test
    void theWalkoverWinnerOfACancelledKnockoutMatchStillOccupiesItsSlot() {
        Match match = givenMatch(match(MatchPhase.SEMIFINAL, MatchStatus.SCHEDULED));
        Team pumas = team(30L, "Pumas");
        Match cancelled = matchBetween(4L, MatchPhase.SEMIFINAL, MatchStatus.CANCELLED, pumas, team(40L, "Bears"));
        cancelled.setCancelReason(CancelReason.NO_SHOW);
        cancelled.setWalkoverWinnerTeam(pumas);
        when(teamService.requireTeam(30L)).thenReturn(pumas);
        when(tournamentService.approvedTeamIds(1L)).thenReturn(List.of(10L, 20L, 30L, 40L));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.SEMIFINAL)).thenReturn(List.of(match, cancelled));

        assertThatThrownBy(() -> service.update(ORGANIZER, 3L, new UpdateMatchRequest(null, null, null, 30L, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El equipo 'Pumas' ya juega otro partido de semifinal.");
        assertThat(match.getHomeTeam().getId()).isEqualTo(10L);
    }

    @Test
    void theLoserOfACancelledKnockoutMatchIsFreeToBeUsed() {
        Match match = givenMatch(match(MatchPhase.SEMIFINAL, MatchStatus.SCHEDULED));
        Team bears = team(40L, "Bears");
        Match cancelled = matchBetween(4L, MatchPhase.SEMIFINAL, MatchStatus.CANCELLED, team(30L, "Pumas"), bears);
        cancelled.setCancelReason(CancelReason.NO_SHOW);
        cancelled.setWalkoverWinnerTeam(cancelled.getHomeTeam());
        when(teamService.requireTeam(40L)).thenReturn(bears);
        when(tournamentService.approvedTeamIds(1L)).thenReturn(List.of(10L, 20L, 30L, 40L));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.SEMIFINAL)).thenReturn(List.of(match, cancelled));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.FINAL)).thenReturn(List.of());

        service.update(ORGANIZER, 3L, new UpdateMatchRequest(null, null, null, 40L, null));

        assertThat(match.getHomeTeam()).isSameAs(bears);
    }

    /** Reopened semi-final whose winner (Tigers) already stands in the final. */
    @Test
    void aTeamAlreadyInTheNextPhaseCannotBeReplacedInAReopenedKnockoutMatch() {
        Match semifinal = givenMatch(match(MatchPhase.SEMIFINAL, MatchStatus.SCHEDULED));
        Match finalMatch = matchBetween(60L, MatchPhase.FINAL, MatchStatus.SCHEDULED,
                semifinal.getHomeTeam(), team(40L, "Bears"));
        when(teamService.requireTeam(30L)).thenReturn(team(30L, "Pumas"));
        when(tournamentService.approvedTeamIds(1L)).thenReturn(List.of(10L, 20L, 30L, 40L));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.SEMIFINAL)).thenReturn(List.of(semifinal));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.FINAL)).thenReturn(List.of(finalMatch));

        assertThatThrownBy(() -> service.update(ORGANIZER, 3L, new UpdateMatchRequest(null, null, null, 30L, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Deshaga la fase final antes de cambiar los equipos de este partido: Tigers ya figura en ella.");
        assertThat(semifinal.getHomeTeam().getId()).isEqualTo(10L);
        verifyNoInteractions(lineups, auditService);
    }

    @Test
    void aTeamSentThroughTheNextPhaseByWalkoverAlsoBlocksTheReplacement() {
        Match semifinal = givenMatch(match(MatchPhase.SEMIFINAL, MatchStatus.SCHEDULED));
        Match finalMatch = matchBetween(60L, MatchPhase.FINAL, MatchStatus.CANCELLED,
                team(50L, "Wolves"), team(40L, "Bears"));
        finalMatch.setCancelReason(CancelReason.NO_SHOW);
        finalMatch.setWalkoverWinnerTeam(semifinal.getAwayTeam());
        when(teamService.requireTeam(30L)).thenReturn(team(30L, "Pumas"));
        when(tournamentService.approvedTeamIds(1L)).thenReturn(List.of(10L, 20L, 30L, 40L, 50L));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.SEMIFINAL)).thenReturn(List.of(semifinal));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.FINAL)).thenReturn(List.of(finalMatch));

        assertThatThrownBy(() -> service.update(ORGANIZER, 3L, new UpdateMatchRequest(null, null, null, null, 30L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Lions ya figura en ella");
    }

    @Test
    void aKnockoutTeamNotInTheNextPhaseCanBeReplaced() {
        Match semifinal = givenMatch(match(MatchPhase.SEMIFINAL, MatchStatus.SCHEDULED));
        Team pumas = team(30L, "Pumas");
        Match finalMatch = matchBetween(60L, MatchPhase.FINAL, MatchStatus.SCHEDULED,
                semifinal.getHomeTeam(), team(40L, "Bears"));
        when(teamService.requireTeam(30L)).thenReturn(pumas);
        when(tournamentService.approvedTeamIds(1L)).thenReturn(List.of(10L, 20L, 30L, 40L));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.SEMIFINAL)).thenReturn(List.of(semifinal));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.FINAL)).thenReturn(List.of(finalMatch));

        service.update(ORGANIZER, 3L, new UpdateMatchRequest(null, null, null, null, 30L));

        assertThat(semifinal.getAwayTeam()).isSameAs(pumas);
        verify(lineups).deleteByMatchIdAndTeamId(3L, 20L);
    }

    @Test
    void aReplacedTeamLosesItsLineupAndTheChangeIsAudited() {
        Match match = givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));
        Team pumas = team(30L, "Pumas");
        when(teamService.requireTeam(30L)).thenReturn(pumas);
        when(tournamentService.approvedTeamIds(1L)).thenReturn(List.of(10L, 20L, 30L));

        var response = service.update(ORGANIZER, 3L, new UpdateMatchRequest(null, null, null, null, 30L));

        assertThat(match.getHomeTeam().getId()).isEqualTo(10L);
        assertThat(match.getAwayTeam()).isSameAs(pumas);
        assertThat(response.awayTeam().id()).isEqualTo(30L);
        assertThat(response.teamsEditable()).isTrue();
        verify(lineups).deleteByMatchIdAndTeamId(3L, 20L);
        verify(lineups, never()).deleteByMatchIdAndTeamId(3L, 10L);
        verify(auditService).record(eq(7L), eq(AuditAction.MATCH_UPDATED), eq("MATCH"), eq(3L),
                argThat(details -> details.get("previousAwayTeamId").equals(20L)
                        && details.get("awayTeamId").equals(30L)
                        && details.get("previousHomeTeamId").equals(10L)
                        && details.get("homeTeamId").equals(10L)));
    }

    @Test
    void swappingHomeAndAwayKeepsBothLineups() {
        Match match = givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));
        when(tournamentService.approvedTeamIds(1L)).thenReturn(List.of(10L, 20L));

        service.update(ORGANIZER, 3L, new UpdateMatchRequest(null, null, null, 20L, 10L));

        assertThat(match.getHomeTeam().getId()).isEqualTo(20L);
        assertThat(match.getAwayTeam().getId()).isEqualTo(10L);
        verifyNoInteractions(lineups);
    }

    @Test
    void theWalkoverWinnerOfACancelledMatchCannotBeReplacedWithoutReopening() {
        Match match = match(MatchPhase.SEMIFINAL, MatchStatus.CANCELLED);
        match.setCancelReason(CancelReason.NO_SHOW);
        match.setWalkoverWinnerTeam(match.getAwayTeam());
        givenMatch(match);
        when(teamService.requireTeam(30L)).thenReturn(team(30L, "Pumas"));
        when(tournamentService.approvedTeamIds(1L)).thenReturn(List.of(10L, 20L, 30L));

        assertThatThrownBy(() -> service.update(ORGANIZER, 3L, new UpdateMatchRequest(null, null, null, null, 30L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("avanzó a la siguiente fase por walkover");
    }

    // --- knockout propagation ---------------------------------------------------------------------

    @Test
    void correctingASemifinalSendsTheNewWinnerToTheFinalInPlaceOfTheLoser() {
        Match semifinal = givenMatch(playedSemifinalLionsWon());
        Match finalMatch = matchBetween(60L, MatchPhase.FINAL, MatchStatus.SCHEDULED,
                semifinal.getAwayTeam(), team(30L, "Pumas"));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.FINAL)).thenReturn(List.of(finalMatch));
        when(userService.getUser(101L)).thenReturn(player(101L));
        when(userService.getUser(102L)).thenReturn(player(102L));

        service.recordResult(ORGANIZER, 3L, new RecordResultRequest(
                2, 0, null, null, List.of(goal(10L, 101L), goal(10L, 102L))));

        assertThat(semifinal.getHomeScore()).isEqualTo(2);
        assertThat(finalMatch.getHomeTeam().getId()).isEqualTo(10L);
        assertThat(finalMatch.getAwayTeam().getId()).isEqualTo(30L);
        verify(lineups).deleteByMatchIdAndTeamId(60L, 20L);
        verify(auditService).record(eq(7L), eq(AuditAction.MATCH_RESULT_CORRECTED), eq("MATCH"), eq(3L), any());
        verify(auditService).record(eq(7L), eq(AuditAction.MATCH_UPDATED), eq("MATCH"), eq(60L),
                argThat(details -> MatchService.RESULT_CORRECTION.equals(details.get("reason"))
                        && details.get("previousTeamId").equals(20L)
                        && details.get("teamId").equals(10L)
                        && details.get("sourceMatchId").equals(3L)));
    }

    /** The refusal happens while planning, before the result, the events or the bracket are touched. */
    @Test
    void aPlayedNextMatchRefusesTheCorrectionBeforeAnythingChanges() {
        Match semifinal = givenMatch(playedSemifinalLionsWon());
        Match finalMatch = matchBetween(60L, MatchPhase.FINAL, MatchStatus.PLAYED,
                semifinal.getAwayTeam(), team(30L, "Pumas"));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.FINAL)).thenReturn(List.of(finalMatch));
        when(userService.getUser(101L)).thenReturn(player(101L));
        when(userService.getUser(102L)).thenReturn(player(102L));

        assertThatThrownBy(() -> service.recordResult(ORGANIZER, 3L, new RecordResultRequest(
                2, 0, null, null, List.of(goal(10L, 101L), goal(10L, 102L)))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El partido de final ya se jugó con Lions; reábralo antes de corregir este resultado.");

        assertThat(semifinal.getStatus()).isEqualTo(MatchStatus.PLAYED);
        assertThat(semifinal.getHomeScore()).isZero();
        assertThat(semifinal.getAwayScore()).isEqualTo(1);
        assertThat(semifinal.getEvents()).isEmpty();
        assertThat(finalMatch.getHomeTeam().getId()).isEqualTo(20L);
        verifyNoInteractions(lineups, auditService);
    }

    @Test
    void aNextMatchCancelledInFavourOfTheLoserAlsoRefusesTheCorrection() {
        Match semifinal = givenMatch(playedSemifinalLionsWon());
        Match finalMatch = matchBetween(60L, MatchPhase.FINAL, MatchStatus.CANCELLED,
                semifinal.getAwayTeam(), team(30L, "Pumas"));
        finalMatch.setCancelReason(CancelReason.NO_SHOW);
        finalMatch.setWalkoverWinnerTeam(semifinal.getAwayTeam());
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.FINAL)).thenReturn(List.of(finalMatch));

        assertThatThrownBy(() -> service.recordResult(ORGANIZER, 3L,
                new RecordResultRequest(0, 0, 5, 4, List.of())))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("fue cancelado dando el pase a Lions");
        verifyNoInteractions(lineups, auditService);
    }

    @Test
    void aCorrectionThatKeepsTheWinnerLeavesTheNextPhaseAlone() {
        Match semifinal = givenMatch(playedSemifinalLionsWon());
        Match finalMatch = matchBetween(60L, MatchPhase.FINAL, MatchStatus.SCHEDULED,
                semifinal.getAwayTeam(), team(30L, "Pumas"));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.FINAL)).thenReturn(List.of(finalMatch));
        when(userService.getUser(201L)).thenReturn(player(201L));
        when(userService.getUser(202L)).thenReturn(player(202L));

        service.recordResult(ORGANIZER, 3L, new RecordResultRequest(
                0, 2, null, null, List.of(goal(20L, 201L), goal(20L, 202L))));

        assertThat(finalMatch.getHomeTeam().getId()).isEqualTo(20L);
        verifyNoInteractions(lineups);
        verify(auditService, never()).record(any(), eq(AuditAction.MATCH_UPDATED), anyString(), any(), any());
    }

    @Test
    void aWalkoverIsPropagatedToTheNextPhaseLikeAResult() {
        Match semifinal = givenMatch(match(MatchPhase.SEMIFINAL, MatchStatus.SCHEDULED));
        Match finalMatch = matchBetween(60L, MatchPhase.FINAL, MatchStatus.SCHEDULED,
                team(30L, "Pumas"), semifinal.getAwayTeam());
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.FINAL)).thenReturn(List.of(finalMatch));

        service.cancel(ORGANIZER, 3L, CancelReason.NO_SHOW, 10L);

        assertThat(semifinal.getWalkoverWinnerTeam().getId()).isEqualTo(10L);
        assertThat(finalMatch.getAwayTeam().getId()).isEqualTo(10L);
        verify(lineups).deleteByMatchIdAndTeamId(60L, 20L);
    }

    @Test
    void aCancellationIsRefusedBeforeLookingAtTheBracketWhenTheMatchIsNotScheduled() {
        givenMatch(match(MatchPhase.SEMIFINAL, MatchStatus.PLAYED));

        assertThatThrownBy(() -> service.cancel(ORGANIZER, 3L, CancelReason.NO_SHOW, 10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("«jugado»");
        verify(matches, never()).findByTournamentIdAndPhaseOrderByIdAsc(any(), any());
    }

    // --- reopen -----------------------------------------------------------------------------------

    @Test
    void reopeningAPlayedMatchClearsItsOutcome() {
        Match match = givenMatch(playedSemifinalLionsWon());
        match.setHomePenalties(3);
        match.setAwayPenalties(4);
        match.addEvent(edu.escuelaing.techcup.competition.domain.MatchEvent.builder()
                .team(match.getAwayTeam()).player(player(201L)).type(EventType.GOAL).minute(5).build());

        var response = service.reopen(ORGANIZER, 3L);

        assertThat(match.getStatus()).isEqualTo(MatchStatus.SCHEDULED);
        assertThat(match.getHomeScore()).isNull();
        assertThat(match.getAwayScore()).isNull();
        assertThat(match.getHomePenalties()).isNull();
        assertThat(match.getAwayPenalties()).isNull();
        assertThat(match.getEvents()).isEmpty();
        assertThat(response.resultEditable()).isTrue();
        assertThat(response.teamsEditable()).isTrue();
        assertThat(response.reopenable()).isFalse();
        verify(auditService).record(eq(7L), eq(AuditAction.MATCH_REOPENED), eq("MATCH"), eq(3L),
                argThat(details -> "PLAYED".equals(details.get("previousStatus"))));
    }

    @Test
    void reopeningACancelledMatchClearsTheReasonAndTheWalkover() {
        Match match = match(MatchPhase.SEMIFINAL, MatchStatus.CANCELLED);
        match.setCancelReason(CancelReason.DISQUALIFIED);
        match.setWalkoverWinnerTeam(match.getHomeTeam());
        givenMatch(match);

        var response = service.reopen(ORGANIZER, 3L);

        assertThat(match.getStatus()).isEqualTo(MatchStatus.SCHEDULED);
        assertThat(match.getCancelReason()).isNull();
        assertThat(match.getWalkoverWinnerTeam()).isNull();
        assertThat(response.walkoverWinnerTeamId()).isNull();
    }

    /** The tournament row is locked before the match is read, so finish cannot slip in between. */
    @Test
    void mutatingAMatchLocksItsTournamentFirst() {
        Match match = givenMatch(playedSemifinalLionsWon());
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.FINAL)).thenReturn(List.of());

        service.reopen(ORGANIZER, 3L);
        service.recordResult(ORGANIZER, 3L, new RecordResultRequest(0, 0, 4, 3, List.of()));

        assertThat(match.getStatus()).isEqualTo(MatchStatus.PLAYED);
        var order = org.mockito.Mockito.inOrder(matches, tournamentService);
        for (int call = 0; call < 2; call++) {
            order.verify(matches).findTournamentIdById(3L);
            order.verify(tournamentService).requireTournamentForUpdate(1L);
            order.verify(matches).findById(3L);
        }
    }

    @Test
    void aScheduledMatchCannotBeReopened() {
        givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));

        assertThatThrownBy(() -> service.reopen(ORGANIZER, 3L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Solo se puede reabrir un partido jugado o cancelado");
    }

    @Test
    void reopeningRequiresTheTournamentToBeInProgress() {
        Match match = match(MatchPhase.FINAL, MatchStatus.PLAYED);
        match.getTournament().setStatus(TournamentStatus.FINISHED);
        givenMatch(match);

        assertThatThrownBy(() -> service.reopen(ORGANIZER, 3L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("mientras el torneo esté en progreso");
        assertThat(match.getStatus()).isEqualTo(MatchStatus.PLAYED);
    }

    // --- undo phase -------------------------------------------------------------------------------

    @Test
    void undoingTheLatestPhaseDeletesItsMatchesAndIsAuditedOnTheTournament() {
        Match group = playedMatch(MatchPhase.GROUP, 1L, 2L, 1, 0);
        Match semiA = matchBetween(70L, MatchPhase.SEMIFINAL, MatchStatus.SCHEDULED, team(1L, "A"), team(4L, "D"));
        Match semiB = matchBetween(71L, MatchPhase.SEMIFINAL, MatchStatus.CANCELLED, team(2L, "B"), team(3L, "C"));
        when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(tournament(TournamentStatus.IN_PROGRESS));
        when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L)).thenReturn(List.of(group, semiA, semiB));

        var response = service.undoPhase(ORGANIZER, 1L);

        assertThat(response.phase()).isEqualTo(MatchPhase.SEMIFINAL);
        assertThat(response.deletedMatches()).isEqualTo(2);
        verify(matches).deleteAll(List.of(semiA, semiB));
        verify(auditService).record(eq(7L), eq(AuditAction.PHASE_UNDONE), eq("TOURNAMENT"), eq(1L),
                argThat(details -> "SEMIFINAL".equals(details.get("phase")) && details.get("deletedMatches").equals(2)));
    }

    @Test
    void aPhaseWithAPlayedMatchCannotBeUndone() {
        Match semi = matchBetween(70L, MatchPhase.SEMIFINAL, MatchStatus.PLAYED, team(1L, "A"), team(4L, "D"));
        when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(tournament(TournamentStatus.IN_PROGRESS));
        when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L))
                .thenReturn(List.of(playedMatch(MatchPhase.GROUP, 1L, 2L, 1, 0), semi));

        assertThatThrownBy(() -> service.undoPhase(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("No se puede deshacer la fase semifinal: ya tiene partidos jugados. Reábralos primero.");
        verify(matches, never()).deleteAll(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void theGroupStageCanBeUndoneWhileNoneOfItsMatchesWasPlayed() {
        Match first = matchBetween(70L, MatchPhase.GROUP, MatchStatus.SCHEDULED, team(1L, "A"), team(2L, "B"));
        Match second = matchBetween(71L, MatchPhase.GROUP, MatchStatus.CANCELLED, team(3L, "C"), team(4L, "D"));
        when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(tournament(TournamentStatus.IN_PROGRESS));
        when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L)).thenReturn(List.of(first, second));

        var response = service.undoPhase(ORGANIZER, 1L);

        assertThat(response.phase()).isEqualTo(MatchPhase.GROUP);
        assertThat(response.deletedMatches()).isEqualTo(2);
    }

    @Test
    void aPlayedGroupStageCannotBeUndone() {
        when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(tournament(TournamentStatus.IN_PROGRESS));
        when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L))
                .thenReturn(List.of(playedMatch(MatchPhase.GROUP, 1L, 2L, 1, 0)));

        assertThatThrownBy(() -> service.undoPhase(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageStartingWith("No se puede deshacer la fase de grupos:");
    }

    @Test
    void thereIsNothingToUndoWithoutMatches() {
        when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(tournament(TournamentStatus.IN_PROGRESS));
        when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.undoPhase(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no hay ninguna fase para deshacer");
    }

    @Test
    void undoingAPhaseRequiresTheTournamentToBeInProgress() {
        when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(tournament(TournamentStatus.FINISHED));

        assertThatThrownBy(() -> service.undoPhase(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("mientras el torneo esté en progreso");
        verifyNoInteractions(matches);
    }

    // --- suspended tournament ------------------------------------------------------------------

    @Test
    void noMatchMayChangeWhileTheTournamentIsSuspended() {
        Match match = givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));
        match.getTournament().suspend();

        assertThatThrownBy(() -> service.recordResult(ORGANIZER, 3L, new RecordResultRequest(
                1, 0, null, null, List.of(goal(10L, 101L)))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("está suspendido")
                .hasMessageContaining("registrar el resultado de un partido")
                .hasMessageContaining("lo reanude");
        assertThatThrownBy(() -> service.update(ORGANIZER, 3L,
                new UpdateMatchRequest(null, null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("modificar un partido");
        assertThatThrownBy(() -> service.cancel(ORGANIZER, 3L, CancelReason.NO_SHOW, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cancelar un partido");
        assertThatThrownBy(() -> service.reopen(ORGANIZER, 3L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("reabrir un partido");
        assertThat(match.getStatus()).isEqualTo(MatchStatus.SCHEDULED);
        verifyNoInteractions(auditService);
    }

    @Test
    void phasesCannotBeAdvancedOrUndoneWhileTheTournamentIsSuspended() {
        Tournament suspended = tournament(TournamentStatus.IN_PROGRESS);
        suspended.suspend();
        when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(suspended);

        assertThatThrownBy(() -> service.advance(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("avanzar de fase");
        assertThatThrownBy(() -> service.undoPhase(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("deshacer una fase");
        verifyNoInteractions(matches);
    }

    // --- automatic knockout rounds -----------------------------------------------------------------

    /** Semi-finals 70 (Pumas 0-2 Bears) and 71 (Tigers v Lions): the last result draws the final, W70 v W71. */
    @Test
    void theLastResultOfAKnockoutPhaseDrawsTheNextOneInBracketOrder() {
        Match first = matchBetween(70L, MatchPhase.SEMIFINAL, MatchStatus.PLAYED,
                team(30L, "Pumas"), team(40L, "Bears"));
        first.setHomeScore(0);
        first.setAwayScore(2);
        Match second = match(MatchPhase.SEMIFINAL, MatchStatus.SCHEDULED);
        second.setId(71L);
        givenMatch(71L, second);
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.SEMIFINAL)).thenReturn(List.of(second, first));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.FINAL)).thenReturn(List.of());
        when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L)).thenReturn(List.of(first, second));
        when(matches.findMaxRoundNumber(1L)).thenReturn(4);
        when(refereeService.activeReferees()).thenReturn(List.of());
        when(teamService.requireTeam(40L)).thenReturn(team(40L, "Bears"));
        when(teamService.requireTeam(10L)).thenReturn(team(10L, "Tigers"));
        when(matches.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userService.getUser(101L)).thenReturn(player(101L));

        service.recordResult(ORGANIZER, 71L, new RecordResultRequest(1, 0, null, null, List.of(goal(10L, 101L))));

        verify(matches).save(argThat(created -> created.getPhase() == MatchPhase.FINAL
                && created.getHomeTeam().getId().equals(40L)
                && created.getAwayTeam().getId().equals(10L)
                && created.getRoundNumber() == 5
                && created.getStatus() == MatchStatus.SCHEDULED));
        verify(auditService).record(eq(7L), eq(AuditAction.MATCHES_GENERATED), eq("TOURNAMENT"), eq(1L),
                argThat(details -> "FINAL".equals(details.get("phase")) && Boolean.TRUE.equals(details.get("automatic"))));
    }

    @Test
    void aResultThatLeavesThePhaseUnfinishedDrawsNothing() {
        Match first = matchBetween(70L, MatchPhase.SEMIFINAL, MatchStatus.SCHEDULED,
                team(30L, "Pumas"), team(40L, "Bears"));
        Match second = givenMatch(match(MatchPhase.SEMIFINAL, MatchStatus.SCHEDULED));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.FINAL)).thenReturn(List.of());
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.SEMIFINAL)).thenReturn(List.of(first, second));
        when(userService.getUser(101L)).thenReturn(player(101L));

        service.recordResult(ORGANIZER, 3L, new RecordResultRequest(1, 0, null, null, List.of(goal(10L, 101L))));

        verify(matches, never()).save(any());
        verify(auditService, never()).record(any(), eq(AuditAction.MATCHES_GENERATED), anyString(), any(), any());
    }

    @Test
    void aWalkoverThatCompletesTheQuarterFinalsDrawsTheSemiFinals() {
        Match qf1 = matchBetween(11L, MatchPhase.QUARTERFINAL, MatchStatus.PLAYED, team(1L, "A"), team(8L, "H"));
        qf1.setHomeScore(1);
        qf1.setAwayScore(0);
        Match qf2 = matchBetween(12L, MatchPhase.QUARTERFINAL, MatchStatus.PLAYED, team(2L, "B"), team(7L, "G"));
        qf2.setHomeScore(0);
        qf2.setAwayScore(3);
        Match qf3 = matchBetween(13L, MatchPhase.QUARTERFINAL, MatchStatus.PLAYED, team(3L, "C"), team(6L, "F"));
        qf3.setHomeScore(2);
        qf3.setAwayScore(2);
        qf3.setHomePenalties(5);
        qf3.setAwayPenalties(4);
        Match qf4 = matchBetween(14L, MatchPhase.QUARTERFINAL, MatchStatus.SCHEDULED, team(4L, "D"), team(5L, "E"));
        givenMatch(14L, qf4);
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.QUARTERFINAL))
                .thenReturn(List.of(qf1, qf2, qf3, qf4));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.SEMIFINAL)).thenReturn(List.of());
        when(matches.findMaxRoundNumber(1L)).thenReturn(5);
        when(refereeService.activeReferees()).thenReturn(List.of());
        for (long id : new long[]{1L, 7L, 3L, 5L}) {
            when(teamService.requireTeam(id)).thenReturn(team(id, "Team " + id));
        }
        java.util.List<Match> saved = new java.util.ArrayList<>();
        when(matches.save(any())).thenAnswer(invocation -> {
            saved.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });

        service.cancel(ORGANIZER, 14L, CancelReason.NO_SHOW, 5L);

        // W1 (A) v W4 (E by walkover), W2 (G) v W3 (C on penalties).
        assertThat(saved).extracting(created -> created.getHomeTeam().getId()).containsExactly(1L, 7L);
        assertThat(saved).extracting(created -> created.getAwayTeam().getId()).containsExactly(5L, 3L);
        assertThat(saved).allSatisfy(created -> assertThat(created.getPhase()).isEqualTo(MatchPhase.SEMIFINAL));
    }

    // --- group matches ----------------------------------------------------------------------------

    @Test
    void aTeamEnteringAGroupMatchMustBelongToThatGroup() {
        Match match = givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));
        match.setGroup(group(5L, "Grupo A"));
        when(teamService.requireTeam(30L)).thenReturn(team(30L, "Pumas"));
        when(tournamentService.approvedTeamIds(1L)).thenReturn(List.of(10L, 20L, 30L));
        when(groupTeams.findTeamIdsByGroupId(5L)).thenReturn(List.of(10L, 20L));

        assertThatThrownBy(() -> service.update(ORGANIZER, 3L, new UpdateMatchRequest(null, null, null, null, 30L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El equipo 'Pumas' no pertenece al Grupo A.");
    }

    @Test
    void aGroupMatchCannotRepeatAPairingOfItsGroup() {
        Match match = givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));
        TournamentGroup groupA = group(5L, "Grupo A");
        match.setGroup(groupA);
        Team pumas = team(30L, "Pumas");
        Match existing = matchBetween(80L, MatchPhase.GROUP, MatchStatus.CANCELLED, pumas, match.getHomeTeam());
        existing.setGroup(groupA);
        when(teamService.requireTeam(30L)).thenReturn(pumas);
        when(tournamentService.approvedTeamIds(1L)).thenReturn(List.of(10L, 20L, 30L));
        when(groupTeams.findTeamIdsByGroupId(5L)).thenReturn(List.of(10L, 20L, 30L));
        when(matches.findByGroupIdOrderByIdAsc(5L)).thenReturn(List.of(match, existing));

        assertThatThrownBy(() -> service.update(ORGANIZER, 3L, new UpdateMatchRequest(null, null, null, null, 30L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("En el Grupo A ya hay un partido entre 'Tigers' y 'Pumas' (partido 80)");
    }

    @Test
    void aGroupMatchMayTakeAnotherTeamOfItsGroup() {
        Match match = givenMatch(match(MatchPhase.GROUP, MatchStatus.SCHEDULED));
        TournamentGroup groupA = group(5L, "Grupo A");
        match.setGroup(groupA);
        when(teamService.requireTeam(30L)).thenReturn(team(30L, "Pumas"));
        when(tournamentService.approvedTeamIds(1L)).thenReturn(List.of(10L, 20L, 30L));
        when(groupTeams.findTeamIdsByGroupId(5L)).thenReturn(List.of(10L, 20L, 30L));
        when(matches.findByGroupIdOrderByIdAsc(5L)).thenReturn(List.of(match));

        var response = service.update(ORGANIZER, 3L, new UpdateMatchRequest(null, null, null, null, 30L));

        assertThat(response.awayTeam().id()).isEqualTo(30L);
        assertThat(response.group().name()).isEqualTo("Grupo A");
    }

    // --- visibility -------------------------------------------------------------------------------

    @Test
    void aMatchOfASuspendedTournamentIsNotFoundForThePublicButVisibleToStaff() {
        Match match = match(MatchPhase.GROUP, MatchStatus.SCHEDULED);
        match.getTournament().suspend();
        when(matches.findById(3L)).thenReturn(Optional.of(match));

        assertThatThrownBy(() -> service.get(3L, null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.get(3L, new AuthenticatedUser(9L, "p@x.co", Set.of("PLAYER"))))
                .isInstanceOf(NotFoundException.class);
        assertThat(service.get(3L, ORGANIZER).id()).isEqualTo(3L);
        assertThat(service.get(3L, new AuthenticatedUser(1L, "a@x.co", Set.of("ADMIN"))).id()).isEqualTo(3L);
    }

    @Test
    void theFixtureListGoesThroughTheTournamentVisibilityCheck() {
        when(tournamentService.requireVisible(1L, null)).thenThrow(NotFoundException.of("el torneo", 1L));

        assertThatThrownBy(() -> service.list(1L, null, null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.bracket(1L, null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.history(1L, null)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(matches);
    }

    // --- a correction never leaves a replaced team in a later round -------------------------------

    /** QF 3 Tigers 0-1 Lions; Lions also won SF 60 (reopened since) and stands in the final 90. */
    @Test
    void aCorrectionCannotReplaceATeamThatAlreadyReachedALaterRound() {
        Match quarter = givenMatch(playedQuarterfinalLionsWon());
        Match semi = matchBetween(60L, MatchPhase.SEMIFINAL, MatchStatus.SCHEDULED,
                quarter.getAwayTeam(), team(30L, "Pumas"));
        Match finalMatch = matchBetween(90L, MatchPhase.FINAL, MatchStatus.SCHEDULED,
                quarter.getAwayTeam(), team(40L, "Bears"));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.SEMIFINAL)).thenReturn(List.of(semi));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.FINAL)).thenReturn(List.of(finalMatch));
        when(userService.getUser(101L)).thenReturn(player(101L));
        when(userService.getUser(102L)).thenReturn(player(102L));

        assertThatThrownBy(() -> service.recordResult(ORGANIZER, 3L, new RecordResultRequest(
                2, 0, null, null, List.of(goal(10L, 101L), goal(10L, 102L)))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El equipo Lions ya avanzó a la final: deshaga o corrija primero esa fase.");

        assertThat(quarter.getHomeScore()).isZero();
        assertThat(quarter.getAwayScore()).isEqualTo(1);
        assertThat(semi.getHomeTeam().getId()).isEqualTo(20L);
        assertThat(finalMatch.getHomeTeam().getId()).isEqualTo(20L);
        verifyNoInteractions(lineups, auditService);
    }

    @Test
    void aWalkoverCannotReplaceATeamThatAlreadyReachedALaterRound() {
        Match quarter = givenMatch(match(MatchPhase.QUARTERFINAL, MatchStatus.SCHEDULED));
        Match semi = matchBetween(60L, MatchPhase.SEMIFINAL, MatchStatus.SCHEDULED,
                quarter.getAwayTeam(), team(30L, "Pumas"));
        Match finalMatch = matchBetween(90L, MatchPhase.FINAL, MatchStatus.CANCELLED,
                team(40L, "Bears"), quarter.getAwayTeam());
        finalMatch.setCancelReason(CancelReason.NO_SHOW);
        finalMatch.setWalkoverWinnerTeam(quarter.getAwayTeam());
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.SEMIFINAL)).thenReturn(List.of(semi));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.FINAL)).thenReturn(List.of(finalMatch));

        assertThatThrownBy(() -> service.cancel(ORGANIZER, 3L, CancelReason.NO_SHOW, 10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El equipo Lions ya avanzó a la final: deshaga o corrija primero esa fase.");

        assertThat(quarter.getStatus()).isEqualTo(MatchStatus.SCHEDULED);
        assertThat(quarter.getWalkoverWinnerTeam()).isNull();
        assertThat(semi.getHomeTeam().getId()).isEqualTo(20L);
        verifyNoInteractions(lineups, auditService);
    }

    /**
     * Reopening is not refused: it changes no other round, and the reopened match can still get its
     * result back (or a walkover) with the same winner; only a different winner is refused.
     */
    @Test
    void aReopenedMatchWhoseWinnerReachedALaterRoundOnlyAcceptsTheSameWinner() {
        Match quarter = givenMatch(playedQuarterfinalLionsWon());
        Match semi = matchBetween(60L, MatchPhase.SEMIFINAL, MatchStatus.SCHEDULED,
                quarter.getAwayTeam(), team(30L, "Pumas"));
        Match finalMatch = matchBetween(90L, MatchPhase.FINAL, MatchStatus.SCHEDULED,
                quarter.getAwayTeam(), team(40L, "Bears"));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.QUARTERFINAL)).thenReturn(List.of(quarter));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.SEMIFINAL)).thenReturn(List.of(semi));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.FINAL)).thenReturn(List.of(finalMatch));
        when(userService.getUser(101L)).thenReturn(player(101L));
        when(userService.getUser(201L)).thenReturn(player(201L));

        service.reopen(ORGANIZER, 3L);
        assertThat(quarter.getStatus()).isEqualTo(MatchStatus.SCHEDULED);

        assertThatThrownBy(() -> service.recordResult(ORGANIZER, 3L, new RecordResultRequest(
                1, 0, null, null, List.of(goal(10L, 101L)))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Lions ya avanzó a la final");
        assertThat(quarter.getStatus()).isEqualTo(MatchStatus.SCHEDULED);

        service.recordResult(ORGANIZER, 3L, new RecordResultRequest(0, 1, null, null, List.of(goal(20L, 201L))));

        assertThat(quarter.getStatus()).isEqualTo(MatchStatus.PLAYED);
        assertThat(semi.getHomeTeam().getId()).isEqualTo(20L);
        verifyNoInteractions(lineups);
    }

    // --- reopening a group match after the knockout started ----------------------------------------

    @Test
    void aGroupMatchCannotBeReopenedOnceTheKnockoutStarted() {
        Match match = givenMatch(playedMatchAsThree(MatchPhase.GROUP));
        when(matches.existsByTournamentIdAndPhaseNot(1L, MatchPhase.GROUP)).thenReturn(true);

        assertThatThrownBy(() -> service.reopen(ORGANIZER, 3L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("La fase de grupos ya terminó: deshaga la eliminatoria antes de reabrir partidos de grupo.");
        assertThat(match.getStatus()).isEqualTo(MatchStatus.PLAYED);
        verifyNoInteractions(auditService);
    }

    @Test
    void aGroupMatchCanBeReopenedBeforeTheKnockout() {
        Match match = givenMatch(playedMatchAsThree(MatchPhase.GROUP));
        when(matches.existsByTournamentIdAndPhaseNot(1L, MatchPhase.GROUP)).thenReturn(false);

        service.reopen(ORGANIZER, 3L);

        assertThat(match.getStatus()).isEqualTo(MatchStatus.SCHEDULED);
    }

    /** Correcting a played group result after the knockout stays allowed: it only changes the tables. */
    @Test
    void aPlayedGroupResultCanStillBeCorrectedOnceTheKnockoutStarted() {
        Match match = givenMatch(playedMatchAsThree(MatchPhase.GROUP));
        when(userService.getUser(101L)).thenReturn(player(101L));

        service.recordResult(ORGANIZER, 3L, new RecordResultRequest(1, 0, null, null, List.of(goal(10L, 101L))));

        assertThat(match.getHomeScore()).isEqualTo(1);
        verify(matches, never()).existsByTournamentIdAndPhaseNot(any(), any());
    }

    // --- generated kick-offs stay inside the accepted window ---------------------------------------

    /** The result stays recorded; the final is simply not drawn, and advance explains why. */
    @Test
    void theAutomaticRoundIsNotDrawnWhenNoDayIsLeftBefore2101() {
        Match first = matchBetween(70L, MatchPhase.SEMIFINAL, MatchStatus.PLAYED,
                team(30L, "Pumas"), team(40L, "Bears"));
        first.setHomeScore(0);
        first.setAwayScore(2);
        first.setScheduledAt(Instant.parse("2100-12-31T18:00:00Z"));
        Match second = match(MatchPhase.SEMIFINAL, MatchStatus.SCHEDULED);
        second.setId(71L);
        givenMatch(71L, second);
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.SEMIFINAL)).thenReturn(List.of(first, second));
        when(matches.findByTournamentIdAndPhaseOrderByIdAsc(1L, MatchPhase.FINAL)).thenReturn(List.of());
        when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L)).thenReturn(List.of(second, first));
        when(userService.getUser(101L)).thenReturn(player(101L));

        service.recordResult(ORGANIZER, 71L, new RecordResultRequest(1, 0, null, null, List.of(goal(10L, 101L))));

        assertThat(second.getStatus()).isEqualTo(MatchStatus.PLAYED);
        verify(matches, never()).save(any());
        verify(auditService, never()).record(any(), eq(AuditAction.MATCHES_GENERATED), anyString(), any(), any());
    }

    @Test
    void advanceRefusesWhenNoDayIsLeftBefore2101() {
        Match first = matchBetween(70L, MatchPhase.SEMIFINAL, MatchStatus.PLAYED,
                team(30L, "Pumas"), team(40L, "Bears"));
        first.setHomeScore(0);
        first.setAwayScore(2);
        first.setScheduledAt(Instant.parse("2100-12-31T18:00:00Z"));
        Match second = matchBetween(71L, MatchPhase.SEMIFINAL, MatchStatus.PLAYED,
                team(10L, "Tigers"), team(20L, "Lions"));
        second.setHomeScore(1);
        second.setAwayScore(0);
        Tournament tournament = first.getTournament();
        when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(tournament);
        when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L)).thenReturn(List.of(second, first));

        assertThatThrownBy(() -> service.advance(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("No hay fechas disponibles antes del año 2101 para programar los partidos.");
        verify(matches, never()).save(any());
    }

    @Test
    void persistRefusesAPlannedDayAfter2100BeforeSavingAnything() {
        Tournament tournament = tournament(TournamentStatus.IN_PROGRESS);
        List<MatchService.PlannedMatch> planned = List.of(
                new MatchService.PlannedMatch(1, 1L, 2L, LocalDate.of(2100, 12, 31)),
                new MatchService.PlannedMatch(2, 1L, 3L, LocalDate.of(2101, 1, 1)));

        assertThatThrownBy(() -> service.persist(tournament, MatchPhase.GROUP, null, planned))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage(MatchService.NO_DATES_LEFT);
        verify(matches, never()).save(any());
    }

    // --- generated matches never double-book a venue or a referee ---------------------------------

    /** Group A already plays at 18:00 on venue 1 with referee 501; group B is generated afterwards. */
    @Test
    void generatedMatchesSkipVenuesAndRefereesBusyAtTheSameKickOff() {
        Tournament tournament = tournament(TournamentStatus.IN_PROGRESS);
        Venue venue1 = Venue.builder().id(1L).name("Cancha 1").build();
        Venue venue2 = Venue.builder().id(2L).name("Cancha 2").build();
        tournament.getVenues().addAll(List.of(venue1, venue2));
        AppUser referee1 = AppUser.builder().id(501L).fullName("Ref 1").build();
        AppUser referee2 = AppUser.builder().id(502L).fullName("Ref 2").build();
        when(refereeService.activeReferees()).thenReturn(List.of(referee1, referee2));
        LocalDate day = TODAY.plusDays(1);
        Instant kickOff = service.kickOff(day);
        Match groupA = matchBetween(80L, MatchPhase.GROUP, MatchStatus.SCHEDULED, team(1L, "A"), team(2L, "B"));
        groupA.setScheduledAt(kickOff);
        groupA.setVenue(venue1);
        groupA.setReferee(referee1);
        // A cancelled match frees its venue and referee.
        Match cancelled = matchBetween(81L, MatchPhase.GROUP, MatchStatus.CANCELLED, team(1L, "A"), team(7L, "G"));
        cancelled.setScheduledAt(kickOff);
        cancelled.setVenue(venue2);
        cancelled.setReferee(referee2);
        when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L)).thenReturn(List.of(groupA, cancelled));
        for (long id = 3L; id <= 6L; id++) {
            when(teamService.requireTeam(id)).thenReturn(team(id, "Team " + id));
        }
        when(matches.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<Match> created = service.persist(tournament, MatchPhase.GROUP, group(6L, "Grupo B"), List.of(
                new MatchService.PlannedMatch(1, 3L, 4L, day),
                new MatchService.PlannedMatch(1, 5L, 6L, day),
                new MatchService.PlannedMatch(2, 3L, 5L, day.plusDays(1))));

        assertThat(created.get(0).getVenue()).isEqualTo(venue2);
        assertThat(created.get(0).getReferee()).isEqualTo(referee2);
        // Everything is busy at that kick-off: left for the organizer instead of double-booked.
        assertThat(created.get(1).getVenue()).isNull();
        assertThat(created.get(1).getReferee()).isNull();
        // Another day: the cycle goes on as before.
        assertThat(created.get(2).getVenue()).isEqualTo(venue1);
        assertThat(created.get(2).getReferee()).isEqualTo(referee1);
    }

    // --- referee view of a suspended tournament ---------------------------------------------------

    @Test
    void aRefereeDoesNotSeeTheMatchesOfASuspendedTournament() {
        AuthenticatedUser referee = new AuthenticatedUser(501L, "ref@x.co", Set.of("REFEREE"));
        Match visible = matchBetween(80L, MatchPhase.GROUP, MatchStatus.SCHEDULED, team(1L, "A"), team(2L, "B"));
        Match hidden = matchBetween(81L, MatchPhase.GROUP, MatchStatus.SCHEDULED, team(3L, "C"), team(4L, "D"));
        hidden.getTournament().suspend();
        when(matches.findByRefereeIdOrderByScheduledAtAscIdAsc(501L)).thenReturn(List.of(visible, hidden));

        assertThat(service.refereeMatches(referee)).extracting(response -> response.id()).containsExactly(80L);
    }

    @Test
    void aSuspendedMatchIsNotVisibleThroughRequireVisibleMatchForNonStaff() {
        Match match = match(MatchPhase.GROUP, MatchStatus.SCHEDULED);
        match.getTournament().suspend();
        when(matches.findById(3L)).thenReturn(Optional.of(match));
        AuthenticatedUser referee = new AuthenticatedUser(501L, "ref@x.co", Set.of("REFEREE"));

        assertThatThrownBy(() -> service.requireVisibleMatch(3L, referee)).isInstanceOf(NotFoundException.class);
        assertThat(service.requireVisibleMatch(3L, ORGANIZER)).isSameAs(match);
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** Quarter-final 3: Tigers (10) 0-1 Lions (20). */
    private static Match playedQuarterfinalLionsWon() {
        Match match = match(MatchPhase.QUARTERFINAL, MatchStatus.PLAYED);
        match.setHomeScore(0);
        match.setAwayScore(1);
        return match;
    }

    /** Match 3 of {@code phase}, Tigers 0-0 Lions, PLAYED. */
    private static Match playedMatchAsThree(MatchPhase phase) {
        Match match = match(phase, MatchStatus.PLAYED);
        match.setHomeScore(0);
        match.setAwayScore(0);
        return match;
    }

    private Match givenMatch(Long id, Match match) {
        lenient().when(matches.findTournamentIdById(id)).thenReturn(Optional.of(1L));
        lenient().when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(match.getTournament());
        when(matches.findById(id)).thenReturn(Optional.of(match));
        return match;
    }

    private static TournamentGroup group(Long id, String name) {
        return TournamentGroup.builder().id(id).name(name).position(0).build();
    }

    private Match givenMatch(Match match) {
        lenient().when(matches.findTournamentIdById(3L)).thenReturn(Optional.of(1L));
        lenient().when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(match.getTournament());
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

    /** Semifinal 3: Tigers (10) 0-1 Lions (20). */
    private static Match playedSemifinalLionsWon() {
        Match match = match(MatchPhase.SEMIFINAL, MatchStatus.PLAYED);
        match.setHomeScore(0);
        match.setAwayScore(1);
        return match;
    }

    private static Match matchBetween(Long id, MatchPhase phase, MatchStatus status, Team home, Team away) {
        return Match.builder()
                .id(id)
                .tournament(tournament(TournamentStatus.IN_PROGRESS))
                .phase(phase)
                .roundNumber(phase.isKnockout() ? 5 : 1)
                .homeTeam(home)
                .awayTeam(away)
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
