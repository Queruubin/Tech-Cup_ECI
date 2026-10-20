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

import edu.escuelaing.techcup.competition.api.dto.CreateMatchRequest;
import edu.escuelaing.techcup.competition.domain.GroupTeam;
import edu.escuelaing.techcup.competition.domain.Match;
import edu.escuelaing.techcup.competition.domain.MatchPhase;
import edu.escuelaing.techcup.competition.domain.MatchStatus;
import edu.escuelaing.techcup.competition.domain.TournamentGroup;
import edu.escuelaing.techcup.competition.infrastructure.GroupTeamRepository;
import edu.escuelaing.techcup.competition.infrastructure.LineupRepository;
import edu.escuelaing.techcup.competition.infrastructure.MatchRepository;
import edu.escuelaing.techcup.competition.infrastructure.TournamentGroupRepository;
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
import edu.escuelaing.techcup.tournaments.domain.Venue;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Groups, team assignments, manual group matches and the per-group round robin. */
@ExtendWith(MockitoExtension.class)
class GroupStageServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final Instant NOW = TODAY.atStartOfDay(ZONE).toInstant();
    private static final AuthenticatedUser ORGANIZER =
            new AuthenticatedUser(7L, "organizer@escuelaing.edu.co", Set.of("ORGANIZER"));

    @Mock
    private TournamentGroupRepository groups;
    @Mock
    private GroupTeamRepository groupTeams;
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
    private AuditService auditService;

    private Tournament tournament;
    private GroupStageService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZONE);
        MatchResponseAssembler assembler = new MatchResponseAssembler();
        MatchService matchService = new MatchService(matches, lineups, groupTeams, tournamentService, teamService,
                userService, refereeService, new KnockoutFixtureStrategy(), assembler, auditService, clock);
        service = new GroupStageService(groups, groupTeams, matches, tournamentService, teamService, matchService,
                new RoundRobinFixtureStrategy(new FixtureShuffler(new java.util.Random(1L))), assembler, auditService);
        tournament = tournament(TournamentStatus.IN_PROGRESS);
        lenient().when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(tournament);
    }

    // --- groups ---------------------------------------------------------------------------------

    @Test
    void groupsAreNamedByLetterTakingTheFirstFreeOne() {
        tournament.setStatus(TournamentStatus.ACTIVE);
        when(groups.findByTournamentIdOrderByPositionAsc(1L)).thenReturn(List.of(group(5L, 0), group(6L, 2)));
        when(groups.save(any())).thenAnswer(invocation -> {
            TournamentGroup saved = invocation.getArgument(0);
            saved.setId(9L);
            return saved;
        });

        var response = service.create(ORGANIZER, 1L);

        assertThat(response.name()).isEqualTo("Grupo B");
        assertThat(response.position()).isEqualTo(1);
        assertThat(response.teams()).isEmpty();
        verify(auditService).record(eq(7L), eq(AuditAction.GROUP_CREATED), eq("TOURNAMENT_GROUP"), eq(9L),
                argThat(details -> "Grupo B".equals(details.get("name"))));
    }

    @Test
    void groupsOnlyChangeWhileTheTournamentIsActiveOrInProgress() {
        tournament.setStatus(TournamentStatus.DRAFT);
        assertThatThrownBy(() -> service.create(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("activo o en progreso")
                .hasMessageContaining("«borrador»");

        tournament.setStatus(TournamentStatus.FINISHED);
        assertThatThrownBy(() -> service.create(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("activo o en progreso");
        verify(groups, never()).save(any());
    }

    @Test
    void groupsAreFrozenOnceTheKnockoutStarted() {
        when(matches.existsByTournamentIdAndPhaseNot(1L, MatchPhase.GROUP)).thenReturn(true);

        assertThatThrownBy(() -> service.create(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("La eliminatoria ya comenzó");
        assertThatThrownBy(() -> service.assignTeam(ORGANIZER, 1L, 5L, 10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("La eliminatoria ya comenzó");
    }

    @Test
    void aSuspendedTournamentRefusesEveryGroupChange() {
        tournament.suspend();

        assertThatThrownBy(() -> service.create(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("está suspendido")
                .hasMessageContaining("crear grupos");
        assertThatThrownBy(() -> service.delete(ORGANIZER, 1L, 5L))
                .hasMessageContaining("está suspendido");
        assertThatThrownBy(() -> service.assignTeam(ORGANIZER, 1L, 5L, 10L))
                .hasMessageContaining("está suspendido");
        assertThatThrownBy(() -> service.removeTeam(ORGANIZER, 1L, 5L, 10L))
                .hasMessageContaining("está suspendido");
        assertThatThrownBy(() -> service.createMatch(ORGANIZER, 1L, request(10L, 20L)))
                .hasMessageContaining("está suspendido");
        assertThatThrownBy(() -> service.roundRobin(ORGANIZER, 1L, 5L))
                .hasMessageContaining("está suspendido");
        verifyNoInteractions(auditService);
    }

    @Test
    void onlyAnEmptyGroupWithoutMatchesCanBeDeleted() {
        TournamentGroup groupA = givenGroup(5L, 0);
        when(matches.existsByGroupId(5L)).thenReturn(true);
        assertThatThrownBy(() -> service.delete(ORGANIZER, 1L, 5L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El Grupo A ya tiene partidos y no se puede eliminar.");

        when(matches.existsByGroupId(5L)).thenReturn(false);
        when(groupTeams.existsByGroupId(5L)).thenReturn(true);
        assertThatThrownBy(() -> service.delete(ORGANIZER, 1L, 5L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Quite primero los equipos del Grupo A");

        when(groupTeams.existsByGroupId(5L)).thenReturn(false);
        service.delete(ORGANIZER, 1L, 5L);
        verify(groups).delete(groupA);
        verify(auditService).record(eq(7L), eq(AuditAction.GROUP_DELETED), eq("TOURNAMENT_GROUP"), eq(5L), any());
    }

    // --- assignments ------------------------------------------------------------------------------

    @Test
    void onlyApprovedTeamsCanBeAssigned() {
        givenGroup(5L, 0);
        when(teamService.requireTeam(10L)).thenReturn(team(10L, "Tigers"));
        when(tournamentService.approvedRegistrationId(1L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignTeam(ORGANIZER, 1L, 5L, 10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El equipo 'Tigers' no tiene una inscripción aprobada en este torneo.");
        verify(groupTeams, never()).save(any());
    }

    @Test
    void anApprovedTeamIsAssignedThroughItsRegistration() {
        givenGroup(5L, 0);
        when(teamService.requireTeam(10L)).thenReturn(team(10L, "Tigers"));
        when(tournamentService.approvedRegistrationId(1L, 10L)).thenReturn(Optional.of(77L));
        when(groupTeams.findByTournamentIdAndTeamId(1L, 10L)).thenReturn(Optional.empty());

        service.assignTeam(ORGANIZER, 1L, 5L, 10L);

        verify(groupTeams).save(argThat(assignment -> assignment.getGroup().getId().equals(5L)
                && assignment.getTournamentId().equals(1L)
                && assignment.getTeam().getId().equals(10L)
                && assignment.getRegistrationId().equals(77L)));
        verify(auditService).record(eq(7L), eq(AuditAction.GROUP_TEAM_ASSIGNED), eq("TOURNAMENT_GROUP"), eq(5L),
                argThat(details -> details.get("teamId").equals(10L) && !details.containsKey("previousGroupId")));
    }

    @Test
    void aTeamWithMatchesInItsGroupCannotBeMovedOrRemoved() {
        TournamentGroup groupA = givenGroup(5L, 0);
        givenGroup(6L, 1);
        Team tigers = team(10L, "Tigers");
        when(teamService.requireTeam(10L)).thenReturn(tigers);
        when(tournamentService.approvedRegistrationId(1L, 10L)).thenReturn(Optional.of(77L));
        GroupTeam assignment = assignment(groupA, tigers);
        when(groupTeams.findByTournamentIdAndTeamId(1L, 10L)).thenReturn(Optional.of(assignment));
        when(matches.existsInGroupForTeam(5L, 10L)).thenReturn(true);

        assertThatThrownBy(() -> service.assignTeam(ORGANIZER, 1L, 6L, 10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("No se puede mover a 'Tigers' del Grupo A: ya tiene partidos en ese grupo.");
        assertThatThrownBy(() -> service.removeTeam(ORGANIZER, 1L, 5L, 10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("No se puede quitar a 'Tigers' del Grupo A: ya tiene partidos en ese grupo.");
        assertThat(assignment.getGroup()).isSameAs(groupA);
        verify(groupTeams, never()).delete(any());
    }

    @Test
    void aTeamWithoutMatchesMovesToAnotherGroup() {
        TournamentGroup groupA = givenGroup(5L, 0);
        TournamentGroup groupB = givenGroup(6L, 1);
        Team tigers = team(10L, "Tigers");
        when(teamService.requireTeam(10L)).thenReturn(tigers);
        when(tournamentService.approvedRegistrationId(1L, 10L)).thenReturn(Optional.of(77L));
        GroupTeam assignment = assignment(groupA, tigers);
        when(groupTeams.findByTournamentIdAndTeamId(1L, 10L)).thenReturn(Optional.of(assignment));
        when(groupTeams.findByTournament(1L)).thenReturn(List.of(assignment));

        var response = service.assignTeam(ORGANIZER, 1L, 6L, 10L);

        assertThat(assignment.getGroup()).isSameAs(groupB);
        assertThat(response.teams()).extracting(team -> team.id()).containsExactly(10L);
        verify(auditService).record(eq(7L), eq(AuditAction.GROUP_TEAM_ASSIGNED), eq("TOURNAMENT_GROUP"), eq(6L),
                argThat(details -> details.get("previousGroupId").equals(5L)));
    }

    @Test
    void assigningATeamToItsOwnGroupChangesNothing() {
        TournamentGroup groupA = givenGroup(5L, 0);
        Team tigers = team(10L, "Tigers");
        when(teamService.requireTeam(10L)).thenReturn(tigers);
        when(tournamentService.approvedRegistrationId(1L, 10L)).thenReturn(Optional.of(77L));
        when(groupTeams.findByTournamentIdAndTeamId(1L, 10L)).thenReturn(Optional.of(assignment(groupA, tigers)));

        service.assignTeam(ORGANIZER, 1L, 5L, 10L);

        verify(groupTeams, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void aTeamWithoutMatchesIsRemovedFromItsGroup() {
        TournamentGroup groupA = givenGroup(5L, 0);
        Team tigers = team(10L, "Tigers");
        GroupTeam assignment = assignment(groupA, tigers);
        when(teamService.requireTeam(10L)).thenReturn(tigers);
        when(groupTeams.findByTournamentIdAndTeamId(1L, 10L)).thenReturn(Optional.of(assignment));

        service.removeTeam(ORGANIZER, 1L, 5L, 10L);

        verify(groupTeams).delete(assignment);
        verify(auditService).record(eq(7L), eq(AuditAction.GROUP_TEAM_REMOVED), eq("TOURNAMENT_GROUP"), eq(5L), any());
    }

    @Test
    void removingATeamThatIsNotInTheGroupIsRefused() {
        givenGroup(5L, 0);
        TournamentGroup groupB = group(6L, 1);
        Team tigers = team(10L, "Tigers");
        when(teamService.requireTeam(10L)).thenReturn(tigers);
        when(groupTeams.findByTournamentIdAndTeamId(1L, 10L)).thenReturn(Optional.of(assignment(groupB, tigers)));

        assertThatThrownBy(() -> service.removeTeam(ORGANIZER, 1L, 5L, 10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El equipo 'Tigers' no pertenece al Grupo A.");
    }

    // --- manual matches ---------------------------------------------------------------------------

    @Test
    void groupMatchesAreCreatedOnlyWhileTheTournamentIsInProgress() {
        tournament.setStatus(TournamentStatus.ACTIVE);

        assertThatThrownBy(() -> service.createMatch(ORGANIZER, 1L, request(10L, 20L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("mientras el torneo esté en progreso");
        assertThatThrownBy(() -> service.roundRobin(ORGANIZER, 1L, 5L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("mientras el torneo esté en progreso");
    }

    @Test
    void groupMatchesAreNotCreatedOnceTheKnockoutStarted() {
        when(matches.existsByTournamentIdAndPhaseNot(1L, MatchPhase.GROUP)).thenReturn(true);

        assertThatThrownBy(() -> service.createMatch(ORGANIZER, 1L, request(10L, 20L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("La eliminatoria ya comenzó");
    }

    @Test
    void theKickOffMustBeAPlausibleDate() {
        givenGroup(5L, 0);

        assertThatThrownBy(() -> service.createMatch(ORGANIZER, 1L, new CreateMatchRequest(5L, 10L, 20L,
                Instant.parse("2101-01-01T00:00:00Z"), null, null)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("La fecha del partido debe estar entre los años 2000 y 2100.");
    }

    @Test
    void bothTeamsMustBelongToTheGroupAndDiffer() {
        givenGroup(5L, 0);
        when(teamService.requireTeam(10L)).thenReturn(team(10L, "Tigers"));
        when(teamService.requireTeam(30L)).thenReturn(team(30L, "Pumas"));
        when(groupTeams.findTeamIdsByGroupId(5L)).thenReturn(List.of(10L, 20L));

        assertThatThrownBy(() -> service.createMatch(ORGANIZER, 1L, request(10L, 30L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El equipo 'Pumas' no pertenece al Grupo A.");
        assertThatThrownBy(() -> service.createMatch(ORGANIZER, 1L, request(10L, 10L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no puede jugar contra sí mismo");
        verify(matches, never()).save(any());
    }

    @Test
    void aPairingIsPlayedOnceInItsGroupWhateverTheOrientation() {
        TournamentGroup groupA = givenGroup(5L, 0);
        Team tigers = team(10L, "Tigers");
        Team lions = team(20L, "Lions");
        when(teamService.requireTeam(10L)).thenReturn(tigers);
        when(teamService.requireTeam(20L)).thenReturn(lions);
        when(groupTeams.findTeamIdsByGroupId(5L)).thenReturn(List.of(10L, 20L));
        when(matches.findByGroupIdOrderByIdAsc(5L)).thenReturn(List.of(match(40L, groupA, lions, tigers)));

        assertThatThrownBy(() -> service.createMatch(ORGANIZER, 1L, request(10L, 20L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("En el Grupo A ya hay un partido entre 'Tigers' y 'Lions' (partido 40). Modifique o "
                        + "reabra ese partido en lugar de crear otro.");
    }

    @Test
    void aValidGroupMatchIsCreatedOnTheNextMatchdayOfItsTeams() {
        TournamentGroup groupA = givenGroup(5L, 0);
        Team tigers = team(10L, "Tigers");
        Team lions = team(20L, "Lions");
        Team pumas = team(30L, "Pumas");
        when(teamService.requireTeam(10L)).thenReturn(tigers);
        when(teamService.requireTeam(20L)).thenReturn(lions);
        when(groupTeams.findTeamIdsByGroupId(5L)).thenReturn(List.of(10L, 20L, 30L));
        when(matches.findByGroupIdOrderByIdAsc(5L)).thenReturn(List.of(match(40L, groupA, tigers, pumas)));
        when(matches.save(any())).thenAnswer(invocation -> {
            Match saved = invocation.getArgument(0);
            saved.setId(41L);
            return saved;
        });
        Instant kickOff = Instant.parse("2026-03-12T23:00:00Z");

        var response = service.createMatch(ORGANIZER, 1L, new CreateMatchRequest(5L, 10L, 20L, kickOff, null, null));

        assertThat(response.group().name()).isEqualTo("Grupo A");
        assertThat(response.phase()).isEqualTo(MatchPhase.GROUP);
        assertThat(response.roundNumber()).isEqualTo(2);
        assertThat(response.scheduledAt()).isEqualTo(kickOff);
        assertThat(response.venue()).isNull();
        assertThat(response.referee()).isNull();
        assertThat(response.status()).isEqualTo(MatchStatus.SCHEDULED);
        verify(auditService).record(eq(7L), eq(AuditAction.MATCH_CREATED), eq("MATCH"), eq(41L),
                argThat(details -> details.get("groupId").equals(5L) && details.get("homeTeamId").equals(10L)));
    }

    // --- round robin inside one group ---------------------------------------------------------------

    @Test
    void aGroupNeedsTwoTeamsForItsRoundRobin() {
        givenGroup(5L, 0);
        when(groupTeams.findTeamIdsByGroupId(5L)).thenReturn(List.of(10L));

        assertThatThrownBy(() -> service.roundRobin(ORGANIZER, 1L, 5L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El Grupo A necesita al menos 2 equipos para generar sus partidos (tiene 1).");
    }

    @Test
    void theRoundRobinCreatesOnlyTheMissingPairingsOfTheGroup() {
        TournamentGroup groupA = givenGroup(5L, 0);
        Team tigers = team(10L, "Tigers");
        Team lions = team(20L, "Lions");
        Team pumas = team(30L, "Pumas");
        when(groupTeams.findTeamIdsByGroupId(5L)).thenReturn(List.of(10L, 20L, 30L));
        when(matches.findByGroupIdOrderByIdAsc(5L)).thenReturn(List.of(match(40L, groupA, lions, tigers)));
        when(refereeService.activeReferees()).thenReturn(List.of());
        lenient().when(teamService.requireTeam(10L)).thenReturn(tigers);
        lenient().when(teamService.requireTeam(20L)).thenReturn(lions);
        lenient().when(teamService.requireTeam(30L)).thenReturn(pumas);
        List<Match> saved = new ArrayList<>();
        when(matches.save(any())).thenAnswer(invocation -> {
            saved.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });

        var created = service.roundRobin(ORGANIZER, 1L, 5L);

        assertThat(created).hasSize(2);
        Set<Set<Long>> pairings = new HashSet<>();
        for (Match match : saved) {
            assertThat(match.getGroup()).isSameAs(groupA);
            assertThat(match.getPhase()).isEqualTo(MatchPhase.GROUP);
            assertThat(match.involves(30L)).isTrue();
            assertThat(match.getScheduledAt().atZone(ZONE).getHour()).isEqualTo(MatchService.KICK_OFF_HOUR);
            pairings.add(Set.of(match.getHomeTeam().getId(), match.getAwayTeam().getId()));
        }
        assertThat(pairings).containsExactlyInAnyOrder(Set.of(10L, 30L), Set.of(20L, 30L));
        verify(auditService).record(eq(7L), eq(AuditAction.MATCHES_GENERATED), eq("TOURNAMENT"), eq(1L),
                argThat(details -> details.get("groupId").equals(5L) && details.get("matches").equals(2)));
    }

    @Test
    void aGroupWithEveryPairingAlreadyScheduledHasNothingToGenerate() {
        TournamentGroup groupA = givenGroup(5L, 0);
        when(groupTeams.findTeamIdsByGroupId(5L)).thenReturn(List.of(10L, 20L));
        when(matches.findByGroupIdOrderByIdAsc(5L))
                .thenReturn(List.of(match(40L, groupA, team(20L, "Lions"), team(10L, "Tigers"))));

        assertThatThrownBy(() -> service.roundRobin(ORGANIZER, 1L, 5L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El Grupo A ya tiene un partido para cada pareja de equipos.");
        verify(matches, never()).save(any());
    }

    /** Both groups kick off at 18:00 on the start date: the second one must not reuse the first's venue or referee. */
    @Test
    void twoGroupsGeneratedOneAfterTheOtherNeverShareAVenueOrARefereeAtTheSameKickOff() {
        givenGroup(5L, 0);
        givenGroup(6L, 1);
        Venue venue = Venue.builder().id(1L).name("Cancha 1").build();
        tournament.getVenues().add(venue);
        AppUser referee = AppUser.builder().id(501L).fullName("Ref").build();
        when(refereeService.activeReferees()).thenReturn(List.of(referee));
        when(groupTeams.findTeamIdsByGroupId(5L)).thenReturn(List.of(10L, 20L));
        when(groupTeams.findTeamIdsByGroupId(6L)).thenReturn(List.of(30L, 40L));
        for (long id : new long[]{10L, 20L, 30L, 40L}) {
            lenient().when(teamService.requireTeam(id)).thenReturn(team(id, "Team " + id));
        }
        List<Match> saved = new ArrayList<>();
        when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L)).thenAnswer(invocation -> List.copyOf(saved));
        when(matches.save(any())).thenAnswer(invocation -> {
            saved.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });

        service.roundRobin(ORGANIZER, 1L, 5L);
        service.roundRobin(ORGANIZER, 1L, 6L);

        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getScheduledAt()).isEqualTo(saved.get(1).getScheduledAt());
        assertThat(saved.get(0).getVenue()).isEqualTo(venue);
        assertThat(saved.get(0).getReferee()).isEqualTo(referee);
        assertThat(saved.get(1).getVenue()).isNull();
        assertThat(saved.get(1).getReferee()).isNull();
    }

    @Test
    void theRoundRobinRefusesKickOffsAfterTheYear2100() {
        TournamentGroup groupA = givenGroup(5L, 0);
        Team tigers = team(10L, "Tigers");
        Team lions = team(20L, "Lions");
        when(groupTeams.findTeamIdsByGroupId(5L)).thenReturn(List.of(10L, 20L, 30L));
        Match last = match(40L, groupA, lions, tigers);
        last.setScheduledAt(Instant.parse("2100-12-31T18:00:00Z"));
        when(matches.findByGroupIdOrderByIdAsc(5L)).thenReturn(List.of(last));

        assertThatThrownBy(() -> service.roundRobin(ORGANIZER, 1L, 5L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("No hay fechas disponibles antes del año 2101 para programar los partidos.");
        verify(matches, never()).save(any());
    }

    // --- helpers ----------------------------------------------------------------------------------

    private TournamentGroup givenGroup(Long id, int position) {
        TournamentGroup group = group(id, position);
        lenient().when(groups.findByIdAndTournamentId(id, 1L)).thenReturn(Optional.of(group));
        return group;
    }

    private TournamentGroup group(Long id, int position) {
        return TournamentGroup.builder().id(id).tournament(tournament).name(TournamentGroup.nameFor(position))
                .position(position).build();
    }

    private static GroupTeam assignment(TournamentGroup group, Team team) {
        return GroupTeam.builder().id(team.getId() + 1000).group(group).tournamentId(1L).team(team)
                .registrationId(77L).build();
    }

    private Match match(Long id, TournamentGroup group, Team home, Team away) {
        return Match.builder().id(id).tournament(tournament).phase(MatchPhase.GROUP).group(group).roundNumber(1)
                .homeTeam(home).awayTeam(away).scheduledAt(NOW.plusSeconds(3600)).status(MatchStatus.SCHEDULED)
                .build();
    }

    private static CreateMatchRequest request(Long homeTeamId, Long awayTeamId) {
        return new CreateMatchRequest(5L, homeTeamId, awayTeamId, NOW.plusSeconds(86_400), null, null);
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
        return Team.builder().id(id).name(name).colors("blue")
                .captain(AppUser.builder().id(id * 100).fullName("Captain " + id).build())
                .status(TeamStatus.ACTIVE).build();
    }
}
