package edu.escuelaing.techcup.teams.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
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
import edu.escuelaing.techcup.shared.exception.NotFoundException;
import edu.escuelaing.techcup.shared.storage.FileDeletionScheduler;
import edu.escuelaing.techcup.shared.storage.FileKind;
import edu.escuelaing.techcup.shared.storage.FileOwner;
import edu.escuelaing.techcup.shared.storage.FileStorage;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.teams.api.dto.CreateTeamRequest;
import edu.escuelaing.techcup.teams.api.dto.UpdateTeamRequest;
import edu.escuelaing.techcup.teams.application.MemberProfilePort.MemberProfile;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.teams.domain.TeamEligibility;
import edu.escuelaing.techcup.teams.domain.TeamStatus;
import edu.escuelaing.techcup.teams.infrastructure.TeamRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

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
    private TeamMatchPort teamMatches;
    @Mock
    private TeamRegistrationPort teamRegistrations;
    @Mock
    private FileStorage fileStorage;
    @Mock
    private FileDeletionScheduler fileDeletion;
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
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(team));
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
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(team));
        when(memberProfiles.findProfile(10L)).thenReturn(Optional.of(profile(10L, 7)));
        when(teams.findActiveTeamByMember(10L)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.addMember(5L, 10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Lions");
    }

    @Test
    void rejectsPlayerWithoutProfile() {
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(teamWithCaptain()));
        when(memberProfiles.findProfile(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addMember(5L, 10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no tiene perfil deportivo");
    }

    @Test
    void rejectsWhenTeamIsFull() {
        Team team = teamWithCaptain();
        while (team.memberCount() < TeamEligibility.MAX_MEMBERS) {
            team.addMember(user(100L + team.memberCount()));
        }
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(team));

        assertThatThrownBy(() -> service.addMember(5L, 10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El equipo 'Tigers' ya está completo (13 integrantes).");
        assertThat(team.memberCount()).isEqualTo(13);
        verify(userService, never()).getUser(10L);
    }

    @Test
    void theThirteenthMemberIsStillAccepted() {
        Team team = teamWithCaptain();
        while (team.memberCount() < TeamEligibility.MAX_MEMBERS - 1) {
            team.addMember(user(100L + team.memberCount()));
        }
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(team));
        when(memberProfiles.findProfile(10L)).thenReturn(Optional.of(profile(10L, 77)));
        when(teams.findActiveTeamByMember(10L)).thenReturn(Optional.empty());
        when(memberProfiles.findProfiles(anyCollection())).thenReturn(Map.of(20L, profile(20L, 9)));
        when(userService.getUser(10L)).thenReturn(user(10L));

        service.addMember(5L, 10L);

        assertThat(team.memberCount()).isEqualTo(13);
    }

    @Test
    void addsAValidMember() {
        Team team = teamWithCaptain();
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(team));
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
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(team));
        when(teamLock.isLocked(5L)).thenReturn(true);

        assertThatThrownBy(() -> service.addMember(5L, 10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El equipo '" + team.getName() + "' está jugando un torneo que ya inició; "
                        + "su plantilla no se puede modificar hasta que el torneo finalice.");
        assertThat(team.hasMember(10L)).isFalse();
        verify(userService, never()).getUser(10L);
    }

    // --- approved in a tournament that has not started: changes keep the team eligible ---------

    @Test
    void anApprovedTeamWhoseTournamentHasNotStartedStillTakesNewMembers() {
        Team team = eligibleTeam(8);
        givenLockedRow(team);
        when(teamLock.upcomingTournamentsOf(5L)).thenReturn(List.of("Copa"));
        givenNewPlayer(user(10L), 77);

        service.addMember(5L, 10L);

        assertThat(team.hasMember(10L)).isTrue();
        assertThat(team.memberCount()).isEqualTo(9);
    }

    @Test
    void anApprovedTeamCannotGrowPastTheMaximumEvenBeforeTheStart() {
        Team team = eligibleTeam(TeamEligibility.MAX_MEMBERS);
        givenLockedRow(team);

        assertThatThrownBy(() -> service.addMember(5L, 10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El equipo 'Tigers' ya está completo (13 integrantes).");
        assertThat(team.memberCount()).isEqualTo(13);
    }

    /** The approval checked the program majority: a new member cannot break it before the start. */
    @Test
    void anApprovedTeamCannotTakeAMemberThatBreaksItsProgramMajority() {
        // 9 members, 5 of them from organizing programs (5 * 2 > 9): eligible. A 10th from OTHER
        // would leave 5 of 10, which is not "strictly more than half".
        Team team = eligibleTeam(9);
        for (long id = 101; id <= 104; id++) {
            long memberId = id;
            team.getMembers().stream().filter(m -> m.userId().equals(memberId))
                    .forEach(m -> m.getUser().setAcademicProgram(AcademicProgram.OTHER));
        }
        givenLockedRow(team);
        when(teamLock.upcomingTournamentsOf(5L)).thenReturn(List.of("Copa"));
        givenNewPlayer(userFrom(10L, AcademicProgram.OTHER), 77);

        assertThatThrownBy(() -> service.addMember(5L, 10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El equipo 'Tigers' está inscrito en el torneo «Copa», que aún no inicia: debe seguir "
                        + "cumpliendo los requisitos para participar y con este cambio no los cumpliría. "
                        + "Más de la mitad de los integrantes deben pertenecer a los programas de Sistemas, "
                        + "Inteligencia Artificial, Ciberseguridad o Estadística (5 de 10 cumplen).");
        assertThat(team.hasMember(10L)).isFalse();
    }

    @Test
    void anApprovedTeamMayLoseAMemberBeforeTheStartWhileItKeepsTheMinimum() {
        Team team = eligibleTeam(9);
        givenLockedRow(team);
        when(teamLock.upcomingTournamentsOf(5L)).thenReturn(List.of("Copa"));
        givenProfilesForAll();

        service.removeMember(CAPTAIN, 5L, 101L);

        assertThat(team.hasMember(101L)).isFalse();
        assertThat(team.memberCount()).isEqualTo(8);
        verify(auditService).record(eq(20L), eq(AuditAction.TEAM_MEMBER_REMOVED), anyString(), eq(5L), any());
    }

    @Test
    void anApprovedTeamCannotDropBelowTheMinimumBeforeTheStart() {
        Team team = eligibleTeam(8);
        givenLockedRow(team);
        when(teamLock.upcomingTournamentsOf(5L)).thenReturn(List.of("Copa"));
        givenProfilesForAll();

        assertThatThrownBy(() -> service.removeMember(CAPTAIN, 5L, 101L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El equipo 'Tigers' está inscrito en el torneo «Copa», que aún no inicia: debe seguir "
                        + "cumpliendo los requisitos para participar y con este cambio no los cumpliría. "
                        + "El equipo tiene 7 integrantes; se requieren al menos 8.");
        assertThat(team.memberCount()).isEqualTo(8);
        verify(auditService, never()).record(any(), any(), anyString(), any(), any());
    }

    @Test
    void aMemberCannotLeaveAnApprovedTeamBelowTheMinimumBeforeTheStart() {
        Team team = eligibleTeam(8);
        givenLockedRow(team);
        when(teamLock.upcomingTournamentsOf(5L)).thenReturn(List.of("Copa", "Liga"));
        givenProfilesForAll();

        assertThatThrownBy(() -> service.leave(memberActor(101L), 5L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageStartingWith("El equipo 'Tigers' está inscrito en los torneos «Copa», «Liga», que aún no "
                        + "inician:")
                .hasMessageEndingWith("El equipo tiene 7 integrantes; se requieren al menos 8.");
        assertThat(team.hasMember(101L)).isTrue();
    }

    @Test
    void aMemberMayLeaveAnApprovedTeamBeforeTheStartWhileItKeepsTheMinimum() {
        Team team = eligibleTeam(9);
        givenLockedRow(team);
        when(teamLock.upcomingTournamentsOf(5L)).thenReturn(List.of("Copa"));
        givenProfilesForAll();

        service.leave(memberActor(101L), 5L);

        assertThat(team.hasMember(101L)).isFalse();
    }

    /** Without an approved registration in a not-started tournament the minimum only gates registration. */
    @Test
    void anUnregisteredTeamMayShrinkBelowTheMinimum() {
        Team team = eligibleTeam(8);
        givenLockedRow(team);

        service.removeMember(CAPTAIN, 5L, 101L);

        assertThat(team.memberCount()).isEqualTo(7);
        verify(memberProfiles, never()).findProfiles(anyCollection());
    }

    @Test
    void anApprovedTeamWhoseTournamentHasNotStartedCanBeRenamed() {
        Team team = teamWithCaptain();
        when(teams.findById(5L)).thenReturn(Optional.of(team));
        when(teams.findByNameIgnoreCase("Pumas")).thenReturn(Optional.empty());

        service.update(CAPTAIN, 5L, new UpdateTeamRequest("Pumas", null));

        assertThat(team.getName()).isEqualTo("Pumas");
        verify(teamLock, never()).upcomingTournamentsOf(any());
    }

    @Test
    void anApprovedTeamWhoseTournamentHasNotStartedCannotBeInactivated() {
        Team team = teamWithCaptain();
        givenLockedRow(team);
        when(teamLock.upcomingTournamentsOf(5L)).thenReturn(List.of("Copa"));

        assertThatThrownBy(() -> service.inactivate(CAPTAIN, 5L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El equipo 'Tigers' está inscrito en el torneo «Copa», que aún no inicia. "
                        + "Un organizador debe retirarlo del torneo antes de inactivarlo.");
        assertThat(team.isActive()).isTrue();
        verify(joinRequests, never()).cancelPendingRequestsOf(any(), any(), anyString());
        verify(roleService, never()).revokeCaptainForClosedTeam(any(), any(), any());
    }

    /** Started (locked): every roster change is refused, whatever the size. */
    @Test
    void aStartedTournamentFreezesRemovalsAndDepartures() {
        Team team = eligibleTeam(10);
        givenLockedRow(team);
        when(teamLock.isLocked(5L)).thenReturn(true);

        assertThatThrownBy(() -> service.removeMember(CAPTAIN, 5L, 101L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("torneo que ya inició");
        assertThatThrownBy(() -> service.leave(memberActor(101L), 5L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("torneo que ya inició");
        assertThat(team.memberCount()).isEqualTo(10);
        verify(teamLock, never()).upcomingTournamentsOf(any());
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
                .hasMessage("El equipo 'Tigers' está jugando un torneo que ya inició, por lo que no se puede "
                        + "modificar hasta que el torneo finalice.");
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
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(team));
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
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(team));
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
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(teamWithCaptain()));
        when(teamLock.isLocked(5L)).thenReturn(true);

        assertThatThrownBy(() -> service.inactivate(CAPTAIN, 5L)).isInstanceOf(BusinessRuleException.class);
        verify(roleService, never()).revokeCaptainForClosedTeam(any(), any(), any());
    }

    /** Inactivation takes the team row lock, so an approval cannot slip in between check and change. */
    @Test
    void inactivationLocksTheTeamRow() {
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(teamWithCaptain()));

        service.inactivate(CAPTAIN, 5L);

        verify(teams).findByIdForUpdate(5L);
        verify(teams, never()).findById(any());
    }

    @Test
    void captainCannotBeRemoved() {
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(teamWithCaptain()));
        when(teamLock.isLocked(5L)).thenReturn(false);

        assertThatThrownBy(() -> service.removeMember(CAPTAIN, 5L, 20L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("capitán");
    }

    // --- create: only real players -------------------------------------------------------------

    @Test
    void anAdminWithoutThePlayerRoleCannotCreateATeam() {
        assertThatThrownBy(() -> service.create(ADMIN, new CreateTeamRequest("Tigers", "orange")))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("rol de jugador");
        verify(teams, never()).save(any());
    }

    // --- delete --------------------------------------------------------------------------------

    @Test
    void onlyAnAdminCanDeleteATeam() {
        assertThatThrownBy(() -> service.delete(CAPTAIN, 5L))
                .isInstanceOf(ForbiddenOperationException.class);
        verify(teams, never()).delete(any());
    }

    @Test
    void aTeamThatPlaysAMatchCannotBeDeleted() {
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(teamWithCaptain()));
        when(teamMatches.tournamentsWithMatchesOf(5L)).thenReturn(List.of("TechCup 2026-1"));

        assertThatThrownBy(() -> service.delete(ADMIN, 5L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("tiene partidos en el torneo «TechCup 2026-1»")
                .hasMessageContaining("Elimine primero ese torneo");
        verify(teams, never()).delete(any());
        verify(teamRegistrations, never()).deleteClosedRegistrationsOf(any());
    }

    @Test
    void aTeamWithALiveRegistrationCannotBeDeleted() {
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(teamWithCaptain()));
        when(teamMatches.tournamentsWithMatchesOf(5L)).thenReturn(List.of());
        when(teamRegistrations.tournamentsWithLiveRegistrationOf(5L)).thenReturn(List.of("A", "B"));

        assertThatThrownBy(() -> service.delete(ADMIN, 5L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("inscripción en revisión o aprobada en los torneos «A», «B»");
        verify(teams, never()).delete(any());
    }

    @Test
    void deletingATeamRemovesItsClosedRegistrationsAndReceiptsAfterCommitAndRevokesCaptain() {
        Team team = teamWithCaptain();
        team.addMember(user(21L));
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(team));
        when(teamMatches.tournamentsWithMatchesOf(5L)).thenReturn(List.of());
        when(teamRegistrations.tournamentsWithLiveRegistrationOf(5L)).thenReturn(List.of());
        team.setPhotoFileId("team-photo");
        when(teamRegistrations.deleteClosedRegistrationsOf(5L)).thenReturn(
                new TeamRegistrationPort.DeletedRegistrations(2, List.of("receipt-1", "evidence-1", "receipt-2")));

        service.delete(ADMIN, 5L);

        verify(fileDeletion).deleteAfterCommit("receipt-1");
        verify(fileDeletion).deleteAfterCommit("receipt-2");
        // The university-form evidence of the closed registrations and the team photo go too.
        verify(fileDeletion).deleteAfterCommit("evidence-1");
        verify(fileDeletion).deleteAfterCommit("team-photo");
        // The delete is flushed before the captain check, which queries the database.
        InOrder order = inOrder(teams, roleService);
        order.verify(teams).delete(team);
        order.verify(teams).flush();
        order.verify(roleService).revokeCaptainForDeletedTeam(1L, 20L, 5L);
        verify(auditService).record(1L, AuditAction.TEAM_DELETED, "TEAM", 5L,
                Map.of("name", "Tigers", "members", 2, "deletedRegistrations", 2));
    }

    // --- eligibility (8 to 13 members) ----------------------------------------------------------

    @Test
    void aTeamOfSevenCannotRegisterButATeamOfEightCan() {
        Team team = teamWithCaptain();
        while (team.memberCount() < 7) {
            team.addMember(user(100L + team.memberCount()));
        }
        when(teams.findById(5L)).thenReturn(Optional.of(team));
        when(memberProfiles.findProfiles(anyCollection())).thenAnswer(inv -> profilesOf(team));

        TeamEligibility.Result seven = service.eligibility(5L);
        assertThat(seven.eligible()).isFalse();
        assertThat(seven.problems()).containsExactly("El equipo tiene 7 integrantes; se requieren al menos 8.");

        team.addMember(user(200L));
        assertThat(service.eligibility(5L).eligible()).isTrue();
    }

    private static Map<Long, MemberProfile> profilesOf(Team team) {
        Map<Long, MemberProfile> profiles = new java.util.HashMap<>();
        List<Long> ids = team.memberIds();
        for (int i = 0; i < ids.size(); i++) {
            profiles.put(ids.get(i), profile(ids.get(i), i + 1));
        }
        return profiles;
    }

    // --- leave ------------------------------------------------------------------------------

    private static final AuthenticatedUser MEMBER = new AuthenticatedUser(21L, "m@escuelaing.edu.co", Set.of("PLAYER"));

    @Test
    void aMemberLeavesTheTeamUnderTheTeamLockAndItIsAudited() {
        Team team = teamWithCaptain();
        team.addMember(user(21L));
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(team));
        when(teamLock.isLocked(5L)).thenReturn(false);

        service.leave(MEMBER, 5L);

        assertThat(team.hasMember(21L)).isFalse();
        assertThat(team.hasMember(20L)).isTrue();
        verify(teams, never()).findById(any());
        verify(auditService).record(21L, AuditAction.TEAM_MEMBER_LEFT, "TEAM", 5L,
                Map.of("userId", 21L, "remainingMembers", 1));
    }

    @Test
    void theCaptainCannotLeaveTheTeam() {
        Team team = teamWithCaptain();
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(team));

        assertThatThrownBy(() -> service.leave(CAPTAIN, 5L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El capitán no puede salir del equipo. Si necesita retirarse, "
                        + "un administrador puede eliminar el equipo.");
        assertThat(team.hasMember(20L)).isTrue();
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void aMemberCannotLeaveATeamLockedByATournament() {
        Team team = teamWithCaptain();
        team.addMember(user(21L));
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(team));
        when(teamLock.isLocked(5L)).thenReturn(true);

        assertThatThrownBy(() -> service.leave(MEMBER, 5L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El equipo 'Tigers' está jugando un torneo que ya inició; "
                        + "no puede salir de él hasta que el torneo finalice.");
        assertThat(team.hasMember(21L)).isTrue();
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void aNonMemberCannotLeaveTheTeam() {
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(teamWithCaptain()));

        assertThatThrownBy(() -> service.leave(MEMBER, 5L))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("Usted no es integrante del equipo 'Tigers'.");
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void leavingAnUnknownTeamIsNotFound() {
        when(teams.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.leave(MEMBER, 99L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theRosterFlagsTheCaptainAndLeavesTheProfileEmptyForMembersWithoutOne() {
        Team team = teamWithCaptain();
        team.addMember(user(10L));
        when(teams.findById(5L)).thenReturn(Optional.of(team));
        when(memberProfiles.findProfiles(anyCollection())).thenReturn(Map.of(20L, profile(20L, 9)));

        List<TeamRosterEntry> roster = service.rosterOf(5L);

        assertThat(roster).hasSize(2);
        TeamRosterEntry captain = roster.stream().filter(TeamRosterEntry::captain).findFirst().orElseThrow();
        assertThat(captain.user().getId()).isEqualTo(20L);
        assertThat(captain.teamName()).isEqualTo("Tigers");
        assertThat(captain.jerseyNumber()).isEqualTo(9);
        assertThat(captain.position()).isEqualTo(Position.MIDFIELDER);
        TeamRosterEntry member = roster.stream().filter(entry -> !entry.captain()).findFirst().orElseThrow();
        assertThat(member.jerseyNumber()).isNull();
        assertThat(member.position()).isNull();
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

    // --- team photo -----------------------------------------------------------------------------

    private static final MockMultipartFile PHOTO =
            new MockMultipartFile("file", "team.png", "image/png", new byte[]{1, 2, 3});
    private static final AuthenticatedUser ORGANIZER = new AuthenticatedUser(2L, "o@escuelaing.edu.co", Set.of("ORGANIZER"));

    @Test
    void theCaptainAnOrganizerAndAnAdminSetTheTeamPhoto() {
        for (AuthenticatedUser editor : new AuthenticatedUser[]{CAPTAIN, ORGANIZER, ADMIN}) {
            Team team = teamWithCaptain();
            when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(team));
            when(fileStorage.store(any(), eq(FileKind.IMAGE), eq(FileOwner.teamPhoto(5L)))).thenReturn("photo-" + editor.id());

            service.replacePhoto(editor, 5L, PHOTO);

            assertThat(team.getPhotoFileId()).isEqualTo("photo-" + editor.id());
            verify(auditService).record(editor.id(), AuditAction.TEAM_PHOTO_UPDATED, "TEAM", 5L,
                    Map.of("photoFileId", "photo-" + editor.id(), "replaced", false));
        }
    }

    @Test
    void anotherMemberOrPlayerCannotChangeTheTeamPhoto() {
        Team team = teamWithCaptain();
        team.addMember(user(21L));
        team.setPhotoFileId("old-photo");
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(team));

        for (AuthenticatedUser other : new AuthenticatedUser[]{MEMBER, STRANGER}) {
            assertThatThrownBy(() -> service.replacePhoto(other, 5L, PHOTO))
                    .isInstanceOf(ForbiddenOperationException.class)
                    .hasMessageContaining("un organizador o un administrador");
            assertThatThrownBy(() -> service.removePhoto(other, 5L))
                    .isInstanceOf(ForbiddenOperationException.class);
        }
        assertThat(team.getPhotoFileId()).isEqualTo("old-photo");
        verify(fileStorage, never()).store(any(), any(), any());
        verify(fileDeletion, never()).deleteAfterCommit(anyString());
    }

    /**
     * The photo is cosmetic: neither the roster freeze of a tournament (an ACTIVE, IN_PROGRESS or
     * SUSPENDED one keeps the team locked) nor its suspension blocks it, so the lock is not even read.
     */
    @Test
    void theTeamPhotoChangesWhileTheTeamIsLockedByAnActiveOrSuspendedTournament() {
        Team team = teamWithCaptain();
        team.setPhotoFileId("old-photo");
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(team));
        org.mockito.Mockito.lenient().when(teamLock.isLocked(5L)).thenReturn(true);
        when(fileStorage.store(any(), eq(FileKind.IMAGE), eq(FileOwner.teamPhoto(5L)))).thenReturn("new-photo");

        service.replacePhoto(ORGANIZER, 5L, PHOTO);

        assertThat(team.getPhotoFileId()).isEqualTo("new-photo");
        verify(teamLock, never()).isLocked(any());
    }

    @Test
    void replacingTheTeamPhotoDeletesThePreviousOneAfterCommit() {
        Team team = teamWithCaptain();
        team.setPhotoFileId("old-photo");
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(team));
        when(fileStorage.store(any(), eq(FileKind.IMAGE), eq(FileOwner.teamPhoto(5L)))).thenReturn("new-photo");

        service.replacePhoto(CAPTAIN, 5L, PHOTO);

        verify(fileDeletion).deleteAfterCommit("old-photo");
        verify(fileStorage, never()).delete(any());
        verify(auditService).record(CAPTAIN.id(), AuditAction.TEAM_PHOTO_UPDATED, "TEAM", 5L,
                Map.of("photoFileId", "new-photo", "replaced", true));
    }

    @Test
    void removingTheTeamPhotoDeletesItAfterCommit() {
        Team team = teamWithCaptain();
        team.setPhotoFileId("old-photo");
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(team));

        service.removePhoto(ADMIN, 5L);

        assertThat(team.getPhotoFileId()).isNull();
        verify(fileDeletion).deleteAfterCommit("old-photo");
        verify(auditService).record(ADMIN.id(), AuditAction.TEAM_PHOTO_REMOVED, "TEAM", 5L,
                Map.of("photoFileId", "old-photo"));
    }

    @Test
    void removingAMissingTeamPhotoIsAConflict() {
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(teamWithCaptain()));

        assertThatThrownBy(() -> service.removePhoto(CAPTAIN, 5L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El equipo 'Tigers' no tiene foto.");
    }

    /** Called by the tournament start: the team row is locked before the requests are cancelled. */
    @Test
    void closingRecruitmentLocksTheTeamAndCancelsPendingRequestsAsRosterFrozen() {
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(teamWithCaptain()));
        when(joinRequests.cancelPendingRequestsOf(1L, 5L, "ROSTER_FROZEN")).thenReturn(3);

        assertThat(service.closeRecruitment(1L, 5L)).isEqualTo(3);
        InOrder order = inOrder(teams, joinRequests);
        order.verify(teams).findByIdForUpdate(5L);
        order.verify(joinRequests).cancelPendingRequestsOf(1L, 5L, "ROSTER_FROZEN");
    }

    // --- helpers for the registered-team rule ----------------------------------------------------

    /** The captain (20) plus members 101, 102... up to {@code size}, all from an organizing program. */
    private static Team eligibleTeam(int size) {
        Team team = teamWithCaptain();
        for (long id = 101; team.memberCount() < size; id++) {
            team.addMember(user(id));
        }
        return team;
    }

    private void givenLockedRow(Team team) {
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(team));
    }

    /** Every user has a profile whose jersey number is their id (so they never clash). */
    private void givenProfilesForAll() {
        when(memberProfiles.findProfiles(anyCollection())).thenAnswer(inv -> {
            Map<Long, MemberProfile> profiles = new java.util.HashMap<>();
            for (Object id : (java.util.Collection<?>) inv.getArgument(0)) {
                profiles.put((Long) id, profile((Long) id, ((Long) id).intValue()));
            }
            return profiles;
        });
    }

    private void givenNewPlayer(AppUser player, int jersey) {
        when(memberProfiles.findProfile(player.getId())).thenReturn(Optional.of(profile(player.getId(), jersey)));
        when(teams.findActiveTeamByMember(player.getId())).thenReturn(Optional.empty());
        when(userService.getUser(player.getId())).thenReturn(player);
        givenProfilesForAll();
    }

    private static AuthenticatedUser memberActor(Long id) {
        return new AuthenticatedUser(id, "m" + id + "@escuelaing.edu.co", Set.of("PLAYER"));
    }

    private static AppUser userFrom(Long id, AcademicProgram program) {
        AppUser user = user(id);
        user.setAcademicProgram(program);
        return user;
    }
}
