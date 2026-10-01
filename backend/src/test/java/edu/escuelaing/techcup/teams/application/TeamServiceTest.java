package edu.escuelaing.techcup.teams.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.identity.application.RoleService;
import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.identity.domain.AcademicProgram;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.players.domain.Position;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.ForbiddenOperationException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.teams.api.dto.CreateTeamRequest;
import edu.escuelaing.techcup.teams.api.dto.UpdateTeamRequest;
import edu.escuelaing.techcup.teams.application.MemberProfilePort.MemberProfile;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.teams.domain.TeamStatus;
import edu.escuelaing.techcup.teams.infrastructure.TeamRepository;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TeamServiceTest {

    private static final AuthenticatedUser CAPTAIN = new AuthenticatedUser(20L, "c@escuelaing.edu.co", Set.of("PLAYER", "CAPTAIN"));
    private static final AuthenticatedUser STRANGER = new AuthenticatedUser(30L, "s@escuelaing.edu.co", Set.of("PLAYER", "CAPTAIN"));
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, "admin@escuelaing.edu.co", Set.of("ADMIN"));
    private static final AuthenticatedUser NEW_PLAYER = new AuthenticatedUser(20L, "c@escuelaing.edu.co", Set.of("PLAYER"));

    @Mock
    private TeamRepository teams;
    @Mock
    private UserService userService;
    @Mock
    private RoleService roleService;
    @Mock
    private MemberProfilePort memberProfiles;
    @Mock
    private TeamLockPort teamLock;
    @Mock
    private TeamJoinRequestPort joinRequests;
    @Mock
    private TeamResponseAssembler assembler;
    @Mock
    private AuditService auditService;
    @InjectMocks
    private TeamService service;

    // --- add member -------------------------------------------------------------------------

    @Test
    void rejectsDuplicateJerseyNumber() {
        Team team = teamWithCaptain();
        when(teams.findById(5L)).thenReturn(Optional.of(team));
        when(memberProfiles.findProfile(10L)).thenReturn(Optional.of(profile(10L, 9)));
        when(teams.findActiveTeamByMember(10L)).thenReturn(Optional.empty());
        when(memberProfiles.findProfiles(anyCollection())).thenReturn(Map.of(20L, profile(20L, 9)));

        assertThatThrownBy(() -> service.addMember(5L, 10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("número de camiseta 9");
        assertThat(team.memberCount()).isEqualTo(1);
    }

    @Test
    void rejectsPlayerAlreadyInAnotherTeam() {
        Team team = teamWithCaptain();
        Team other = Team.builder().id(6L).name("Lions").captain(user(40L)).status(TeamStatus.ACTIVE).build();
        when(teams.findById(5L)).thenReturn(Optional.of(team));
        when(memberProfiles.findProfile(10L)).thenReturn(Optional.of(profile(10L, 7)));
        when(teams.findActiveTeamByMember(10L)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.addMember(5L, 10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Lions");
    }

    @Test
    void rejectsPlayerWithoutProfile() {
        when(teams.findById(5L)).thenReturn(Optional.of(teamWithCaptain()));
        when(memberProfiles.findProfile(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addMember(5L, 10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no tiene perfil deportivo");
    }

    @Test
    void rejectsWhenTeamIsFull() {
        Team team = teamWithCaptain();
        for (long id = 100; id < 111; id++) {
            team.addMember(user(id));
        }
        when(teams.findById(5L)).thenReturn(Optional.of(team));

        assertThatThrownBy(() -> service.addMember(5L, 10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ya está completo");
    }

    @Test
    void addsAValidMember() {
        Team team = teamWithCaptain();
        when(teams.findById(5L)).thenReturn(Optional.of(team));
        when(memberProfiles.findProfile(10L)).thenReturn(Optional.of(profile(10L, 7)));
        when(teams.findActiveTeamByMember(10L)).thenReturn(Optional.empty());
        when(memberProfiles.findProfiles(anyCollection())).thenReturn(Map.of(20L, profile(20L, 9)));
        when(userService.getUser(10L)).thenReturn(user(10L));

        service.addMember(5L, 10L);

        assertThat(team.hasMember(10L)).isTrue();
        assertThat(team.memberCount()).isEqualTo(2);
    }

    @Test
    void aLockedTeamTakesNoNewMembers() {
        Team team = teamWithCaptain();
        when(teams.findById(5L)).thenReturn(Optional.of(team));
        when(teamLock.isLocked(5L)).thenReturn(true);

        assertThatThrownBy(() -> service.addMember(5L, 10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El equipo '" + team.getName() + "' está inscrito en un torneo activo o en curso; "
                        + "su plantilla no se puede modificar hasta que el torneo finalice.");
        assertThat(team.hasMember(10L)).isFalse();
        verify(userService, never()).getUser(10L);
    }

    // --- create ------------------------------------------------------------------------------

    @Test
    void captainNeedsASportProfileToCreateATeam() {
        when(teams.existsByNameIgnoreCase("Tigers")).thenReturn(false);
        when(userService.getUser(20L)).thenReturn(user(20L));
        when(memberProfiles.findProfile(20L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(CAPTAIN, new CreateTeamRequest("Tigers", "orange")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("perfil deportivo");
        verify(teams, never()).save(any());
        verify(roleService, never()).grantCaptainForNewTeam(any(), any());
    }

    @Test
    void captainBecomesTheFirstMember() {
        when(teams.existsByNameIgnoreCase("Tigers")).thenReturn(false);
        when(userService.getUser(20L)).thenReturn(user(20L));
        when(memberProfiles.findProfile(20L)).thenReturn(Optional.of(profile(20L, 1)));
        when(teams.findActiveTeamByMember(20L)).thenReturn(Optional.empty());
        when(teams.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.create(CAPTAIN, new CreateTeamRequest(" Tigers ", "orange"));

        verify(teams).save(any(Team.class));
        verify(assembler).toResponse(any(Team.class));
    }

    @Test
    void anyPlayerCreatingATeamBecomesItsCaptain() {
        when(teams.existsByNameIgnoreCase("Tigers")).thenReturn(false);
        when(userService.getUser(20L)).thenReturn(user(20L));
        when(memberProfiles.findProfile(20L)).thenReturn(Optional.of(profile(20L, 1)));
        when(teams.findActiveTeamByMember(20L)).thenReturn(Optional.empty());
        when(teams.save(any())).thenAnswer(inv -> {
            Team saved = inv.getArgument(0);
            saved.setId(5L);
            return saved;
        });

        service.create(NEW_PLAYER, new CreateTeamRequest("Tigers", "orange"));

        verify(roleService).grantCaptainForNewTeam(20L, 5L);
        verify(auditService).record(eq(20L), eq(AuditAction.TEAM_CREATED), anyString(), eq(5L), any());
    }

    @Test
    void aPlayerAlreadyInATeamCannotCreateAnother() {
        Team other = Team.builder().id(6L).name("Lions").captain(user(40L)).status(TeamStatus.ACTIVE).build();
        when(teams.existsByNameIgnoreCase("Tigers")).thenReturn(false);
        when(userService.getUser(20L)).thenReturn(user(20L));
        when(memberProfiles.findProfile(20L)).thenReturn(Optional.of(profile(20L, 1)));
        when(teams.findActiveTeamByMember(20L)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.create(NEW_PLAYER, new CreateTeamRequest("Tigers", "orange")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Lions");
        verify(roleService, never()).grantCaptainForNewTeam(any(), any());
    }

    // --- lock and ownership ------------------------------------------------------------------

    @Test
    void lockedTeamCannotBeUpdated() {
        when(teams.findById(5L)).thenReturn(Optional.of(teamWithCaptain()));
        when(teamLock.isLocked(5L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(CAPTAIN, 5L, new UpdateTeamRequest("New", null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("torneo activo o en progreso");
    }

    @Test
    void onlyTheCaptainCanUpdate() {
        when(teams.findById(5L)).thenReturn(Optional.of(teamWithCaptain()));

        assertThatThrownBy(() -> service.update(STRANGER, 5L, new UpdateTeamRequest("New", null)))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void inactivatingATeamCancelsItsPendingJoinRequests() {
        Team team = teamWithCaptain();
        when(teams.findById(5L)).thenReturn(Optional.of(team));
        when(teamLock.isLocked(5L)).thenReturn(false);
        when(joinRequests.cancelPendingRequestsOf(20L, 5L, "TEAM_INACTIVATED")).thenReturn(2);

        service.inactivate(CAPTAIN, 5L);

        assertThat(team.isActive()).isFalse();
        verify(joinRequests).cancelPendingRequestsOf(20L, 5L, "TEAM_INACTIVATED");
        verify(auditService).record(eq(20L), eq(AuditAction.TEAM_INACTIVATED), anyString(), eq(5L),
                eq(Map.of("cancelledJoinRequests", 2)));
    }

    @Test
    void inactivatingATeamRevokesCaptainFromItsCaptainAfterItIsInactive() {
        Team team = teamWithCaptain();
        when(teams.findById(5L)).thenReturn(Optional.of(team));
        when(teamLock.isLocked(5L)).thenReturn(false);
        doAnswer(inv -> {
            assertThat(team.isActive()).as("team already inactive when CAPTAIN is revoked").isFalse();
            return null;
        }).when(roleService).revokeCaptainForClosedTeam(1L, 20L, 5L);

        service.inactivate(ADMIN, 5L);

        verify(roleService).revokeCaptainForClosedTeam(1L, 20L, 5L);
    }

    @Test
    void aLockedTeamIsNotInactivatedAndKeepsItsCaptain() {
        when(teams.findById(5L)).thenReturn(Optional.of(teamWithCaptain()));
        when(teamLock.isLocked(5L)).thenReturn(true);

        assertThatThrownBy(() -> service.inactivate(CAPTAIN, 5L)).isInstanceOf(BusinessRuleException.class);
        verify(roleService, never()).revokeCaptainForClosedTeam(any(), any(), any());
    }

    @Test
    void captainCannotBeRemoved() {
        when(teams.findById(5L)).thenReturn(Optional.of(teamWithCaptain()));
        when(teamLock.isLocked(5L)).thenReturn(false);

        assertThatThrownBy(() -> service.removeMember(CAPTAIN, 5L, 20L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("capitán");
    }

    private static Team teamWithCaptain() {
        AppUser captain = user(20L);
        Team team = Team.builder().id(5L).name("Tigers").colors("orange").captain(captain)
                .status(TeamStatus.ACTIVE).build();
        team.addMember(captain);
        return team;
    }

    private static AppUser user(Long id) {
        return AppUser.builder().id(id).fullName("User " + id).academicProgram(AcademicProgram.SYSTEMS_ENGINEERING).build();
    }

    private static MemberProfile profile(Long userId, int jersey) {
        return new MemberProfile(userId, Position.MIDFIELDER, jersey, null);
    }

    @Test
    void closingRecruitmentCancelsPendingRequestsAsRosterFrozen() {
        when(joinRequests.cancelPendingRequestsOf(1L, 5L, "ROSTER_FROZEN")).thenReturn(3);

        assertThat(service.closeRecruitment(1L, 5L)).isEqualTo(3);
        verify(joinRequests).cancelPendingRequestsOf(1L, 5L, "ROSTER_FROZEN");
    }

}
