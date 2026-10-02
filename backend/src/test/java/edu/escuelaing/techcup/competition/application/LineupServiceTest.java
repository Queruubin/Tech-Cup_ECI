package edu.escuelaing.techcup.competition.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.competition.api.dto.LineupRequest;
import edu.escuelaing.techcup.competition.api.dto.LineupResponse;
import edu.escuelaing.techcup.competition.domain.Formation;
import edu.escuelaing.techcup.competition.domain.Lineup;
import edu.escuelaing.techcup.competition.domain.Match;
import edu.escuelaing.techcup.competition.domain.MatchPhase;
import edu.escuelaing.techcup.competition.domain.MatchStatus;
import edu.escuelaing.techcup.competition.infrastructure.LineupRepository;
import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.ForbiddenOperationException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.teams.application.MemberProfilePort;
import edu.escuelaing.techcup.teams.application.TeamService;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.teams.domain.TeamStatus;
import edu.escuelaing.techcup.tournaments.domain.Tournament;
import edu.escuelaing.techcup.tournaments.domain.TournamentStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Lineup rules: exactly seven starters, all of them team members, submitted before kick-off. */
@ExtendWith(MockitoExtension.class)
class LineupServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final Instant NOW = TODAY.atStartOfDay(ZONE).toInstant();
    /** The home team's members are 101..110; 101 is the captain. */
    private static final AuthenticatedUser CAPTAIN =
            new AuthenticatedUser(101L, "captain@escuelaing.edu.co", Set.of("PLAYER", "CAPTAIN"));
    private static final AuthenticatedUser OUTSIDER =
            new AuthenticatedUser(999L, "other@escuelaing.edu.co", Set.of("PLAYER", "CAPTAIN"));
    private static final List<Long> SEVEN_STARTERS = List.of(101L, 102L, 103L, 104L, 105L, 106L, 107L);

    @Mock
    private LineupRepository lineups;
    @Mock
    private MatchService matchService;
    @Mock
    private TeamService teamService;
    @Mock
    private UserService userService;
    @Mock
    private MemberProfilePort memberProfiles;
    @Mock
    private AuditService auditService;

    private LineupService service;

    @BeforeEach
    void setUp() {
        service = new LineupService(lineups, matchService, teamService, userService, memberProfiles,
                auditService, Clock.fixed(NOW, ZONE));
    }

    @Test
    void exactlySevenStartersAreRequired() {
        givenMatch(MatchStatus.SCHEDULED, NOW.plusSeconds(3600));

        assertThatThrownBy(() -> service.save(CAPTAIN, 3L,
                new LineupRequest(Formation.F_2_3_1, List.of(101L, 102L, 103L, 104L, 105L, 106L))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("exactamente 7 titulares");
        verify(lineups, never()).save(any());
    }

    @Test
    void moreThanSevenStartersAreRefused() {
        givenMatch(MatchStatus.SCHEDULED, NOW.plusSeconds(3600));

        assertThatThrownBy(() -> service.save(CAPTAIN, 3L, new LineupRequest(Formation.F_2_3_1,
                List.of(101L, 102L, 103L, 104L, 105L, 106L, 107L, 108L))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("exactamente 7 titulares");
    }

    @Test
    void repeatedStartersAreRefused() {
        givenMatch(MatchStatus.SCHEDULED, NOW.plusSeconds(3600));

        assertThatThrownBy(() -> service.save(CAPTAIN, 3L, new LineupRequest(Formation.F_2_3_1,
                List.of(101L, 101L, 103L, 104L, 105L, 106L, 107L))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("jugadores repetidos");
    }

    @Test
    void astarterMustBeAmemberOfTheTeam() {
        givenMatch(MatchStatus.SCHEDULED, NOW.plusSeconds(3600));

        assertThatThrownBy(() -> service.save(CAPTAIN, 3L, new LineupRequest(Formation.F_2_3_1,
                List.of(101L, 102L, 103L, 104L, 105L, 106L, 777L))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no son integrantes del equipo 'Tigers'");
    }

    @Test
    void thelineupCannotBeSubmittedAfterKickOff() {
        givenMatch(MatchStatus.SCHEDULED, NOW.minusSeconds(60));

        assertThatThrownBy(() -> service.save(CAPTAIN, 3L, new LineupRequest(null, SEVEN_STARTERS)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("antes de que inicie el partido");
    }

    @Test
    void thelineupOfAplayedMatchCannotBeChanged() {
        givenMatch(MatchStatus.PLAYED, NOW.plusSeconds(3600));

        assertThatThrownBy(() -> service.save(CAPTAIN, 3L, new LineupRequest(null, SEVEN_STARTERS)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("partido jugado");
    }

    @Test
    void onlyAcaptainOfOneOfTheTwoTeamsMaySubmitAlineup() {
        givenMatch(MatchStatus.SCHEDULED, NOW.plusSeconds(3600));

        assertThatThrownBy(() -> service.save(OUTSIDER, 3L, new LineupRequest(null, SEVEN_STARTERS)))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("capitán de uno de los dos equipos");
    }

    @Test
    void avalidLineupSplitsStartersFromSubstitutesAndDefaultsTheFormation() {
        givenMatch(MatchStatus.SCHEDULED, NOW.plusSeconds(3600));
        when(lineups.findByMatchIdAndTeamId(3L, 10L)).thenReturn(Optional.empty());
        when(lineups.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userService.getUser(anyLong())).thenAnswer(invocation -> player(invocation.getArgument(0)));
        when(memberProfiles.findProfiles(anyCollection())).thenReturn(Map.of());

        LineupResponse response = service.save(CAPTAIN, 3L, new LineupRequest(null, SEVEN_STARTERS));

        assertThat(response.formation()).isEqualTo(Formation.F_2_3_1);
        assertThat(response.starters()).hasSize(Lineup.STARTERS);
        assertThat(response.starters()).extracting(LineupResponse.Player::userId)
                .containsExactlyInAnyOrderElementsOf(SEVEN_STARTERS);
        assertThat(response.substitutes()).extracting(LineupResponse.Player::userId)
                .containsExactly(108L, 109L, 110L);
    }

    // --- read authorization ---------------------------------------------------------------------

    @Test
    void arivalCannotReadAlineup() {
        givenMatch(MatchStatus.SCHEDULED, NOW.plusSeconds(3600));
        when(teamService.requireTeam(10L)).thenReturn(homeTeam());

        assertThatThrownBy(() -> service.find(OUTSIDER, 3L, 10L))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Solo los integrantes del equipo");
    }

    @Test
    void anOrganizerCanReadAnyLineup() {
        givenMatch(MatchStatus.SCHEDULED, NOW.plusSeconds(3600));
        AuthenticatedUser organizer = new AuthenticatedUser(7L, "o@escuelaing.edu.co", Set.of("ORGANIZER"));
        when(lineups.findByMatchIdAndTeamId(3L, 10L)).thenReturn(Optional.empty());

        assertThat(service.find(organizer, 3L, 10L)).isEmpty();
        verify(teamService, never()).requireTeam(anyLong());
    }

    @Test
    void alineupIsOnlyReadableForAteamThatPlaysTheMatch() {
        givenMatch(MatchStatus.SCHEDULED, NOW.plusSeconds(3600));

        assertThatThrownBy(() -> service.find(CAPTAIN, 3L, 55L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no juega este partido");
    }

    // --- helpers ---------------------------------------------------------------------------------

    private void givenMatch(MatchStatus status, Instant scheduledAt) {
        // Saving locks the tournament (requireMatchLockingTournament); reading does not (requireMatch).
        Match match = match(status, scheduledAt);
        lenient().when(matchService.requireMatchLockingTournament(3L)).thenReturn(match);
        lenient().when(matchService.requireMatch(3L)).thenReturn(match);
        lenient().when(teamService.requireTeam(10L)).thenReturn(homeTeam());
    }

    private static Match match(MatchStatus status, Instant scheduledAt) {
        return Match.builder()
                .id(3L)
                .tournament(Tournament.builder().id(1L).name("TechCup").startDate(TODAY)
                        .endDate(TODAY.plusDays(30)).registrationDeadline(TODAY.minusDays(5))
                        .maxTeams(8).fee(BigDecimal.TEN).status(TournamentStatus.IN_PROGRESS).build())
                .phase(MatchPhase.GROUP)
                .roundNumber(1)
                .homeTeam(homeTeam())
                .awayTeam(Team.builder().id(20L).name("Lions").colors("red").captain(player(201L))
                        .status(TeamStatus.ACTIVE).build())
                .scheduledAt(scheduledAt)
                .status(status)
                .build();
    }

    private static Team homeTeam() {
        Team team = Team.builder().id(10L).name("Tigers").colors("orange").captain(player(101L))
                .status(TeamStatus.ACTIVE).build();
        LongStream.rangeClosed(101, 110).forEach(id -> team.addMember(player(id)));
        return team;
    }

    private static AppUser player(Long id) {
        return AppUser.builder().id(id).fullName("Player " + id).build();
    }
}
