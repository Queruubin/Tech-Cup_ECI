package edu.escuelaing.techcup.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.identity.domain.Role;
import edu.escuelaing.techcup.identity.domain.UserStatus;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.ForbiddenOperationException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, "admin@escuelaing.edu.co", Set.of("ADMIN"));
    private static final AuthenticatedUser ORGANIZER = new AuthenticatedUser(2L, "org@escuelaing.edu.co", Set.of("ORGANIZER"));

    @Mock
    private UserService userService;
    @Mock
    private PlayerAgePolicy playerAgePolicy;
    @Mock
    private AuditService auditService;
    @InjectMocks
    private RoleService roleService;

    // --- manual role management (ADMIN only) --------------------------------------------------

    @Test
    void organizerCannotGrantAdmin() {
        assertThatThrownBy(() -> roleService.assignRole(ORGANIZER, 10L, Role.ADMIN))
                .isInstanceOf(ForbiddenOperationException.class);
        verify(userService, never()).getUser(anyLong());
    }

    @Test
    void organizerCannotGrantOrganizer() {
        assertThatThrownBy(() -> roleService.assignRole(ORGANIZER, 10L, Role.ORGANIZER))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void organizersNoLongerAppointCaptains() {
        assertThatThrownBy(() -> roleService.assignRole(ORGANIZER, 10L, Role.CAPTAIN))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> roleService.removeRole(ORGANIZER, 10L, Role.CAPTAIN))
                .isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(userService, auditService);
    }

    @Test
    void adminCanGrantCaptainToAPlayer() {
        when(userService.getUser(10L)).thenReturn(user(10L, Role.PLAYER));

        assertThat(roleService.assignRole(ADMIN, 10L, Role.CAPTAIN)).containsExactly(Role.PLAYER, Role.CAPTAIN);
        verify(auditService).record(eq(1L), eq(AuditAction.ROLE_ASSIGNED), anyString(), eq(10L), any());
    }

    @Test
    void captainRequiresPlayerRole() {
        when(userService.getUser(10L)).thenReturn(user(10L, Role.GUEST));

        assertThatThrownBy(() -> roleService.assignRole(ADMIN, 10L, Role.CAPTAIN))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Solo un jugador");
    }

    @Test
    void adminCanGrantAnyRole() {
        when(userService.getUser(10L)).thenReturn(user(10L, Role.GUEST));

        assertThat(roleService.assignRole(ADMIN, 10L, Role.ORGANIZER)).contains(Role.ORGANIZER);
        verifyNoInteractions(playerAgePolicy);
    }

    @Test
    void assigningPlayerChecksTheAgeRange() {
        AppUser adult = user(10L, Role.GUEST);
        adult.setBirthDate(LocalDate.of(1990, 1, 1));
        when(userService.getUser(10L)).thenReturn(adult);
        doThrow(new BusinessRuleException("Para ser jugador la edad debe estar entre 5 y 100 años."))
                .when(playerAgePolicy).validate(LocalDate.of(1990, 1, 1));

        assertThatThrownBy(() -> roleService.assignRole(ADMIN, 10L, Role.PLAYER))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("entre 5 y 100 años");
        assertThat(adult.getRoles()).containsExactly(Role.GUEST);
        verify(auditService, never()).record(any(), any(), anyString(), anyLong(), any());
    }

    @Test
    void assigningPlayerWithinTheAgeRangeSucceeds() {
        AppUser child = user(10L, Role.GUEST);
        child.setBirthDate(LocalDate.of(2016, 1, 1));
        when(userService.getUser(10L)).thenReturn(child);

        assertThat(roleService.assignRole(ADMIN, 10L, Role.PLAYER)).containsExactly(Role.GUEST, Role.PLAYER);
        verify(playerAgePolicy).validate(LocalDate.of(2016, 1, 1));
    }

    @Test
    void adminCannotRemoveOwnAdminRole() {
        assertThatThrownBy(() -> roleService.removeRole(ADMIN, 1L, Role.ADMIN))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("quitarse a sí mismo el rol de administrador");
        verify(userService, never()).getUser(anyLong());
    }

    @Test
    void adminCanRemoveAnotherAdminsRole() {
        when(userService.getUser(3L)).thenReturn(user(3L, Role.ADMIN, Role.PLAYER));

        assertThat(roleService.removeRole(ADMIN, 3L, Role.ADMIN)).containsExactly(Role.PLAYER);
        verify(auditService).record(eq(1L), eq(AuditAction.ROLE_REMOVED), anyString(), eq(3L), any());
    }

    @Test
    void captainCannotBeRevokedWhileTheUserCaptainsAnActiveTeam() {
        when(userService.getUser(10L)).thenReturn(user(10L, Role.PLAYER, Role.CAPTAIN));
        when(userService.captainsActiveTeam(10L)).thenReturn(true);

        assertThatThrownBy(() -> roleService.removeRole(ADMIN, 10L, Role.CAPTAIN))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("capitán de un equipo activo");
        verify(auditService, never()).record(any(), any(), anyString(), anyLong(), any());
    }

    @Test
    void captainCanBeRevokedOnceTheTeamIsGone() {
        when(userService.getUser(10L)).thenReturn(user(10L, Role.PLAYER, Role.CAPTAIN));
        when(userService.captainsActiveTeam(10L)).thenReturn(false);

        assertThat(roleService.removeRole(ADMIN, 10L, Role.CAPTAIN)).containsExactly(Role.PLAYER);
    }

    @Test
    void playerAndCaptainStayWhileTheUserPlaysATournament() {
        AppUser user = user(10L, Role.PLAYER, Role.CAPTAIN);
        when(userService.getUser(10L)).thenReturn(user);
        when(userService.isLockedByTournament(10L)).thenReturn(true);

        for (Role role : new Role[] {Role.PLAYER, Role.CAPTAIN}) {
            assertThatThrownBy(() -> roleService.removeRole(ADMIN, 10L, role))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessage("El usuario está inscrito con su equipo en un torneo activo o en curso; "
                            + "no se pueden cambiar sus roles de jugador o capitán hasta que el torneo finalice.");
        }
        assertThat(user.getRoles()).containsExactlyInAnyOrder(Role.PLAYER, Role.CAPTAIN);
        verify(auditService, never()).record(any(), any(), anyString(), anyLong(), any());
    }

    @Test
    void playerCannotBeRevokedWhileTheUserBelongsToOrCaptainsAnActiveTeam() {
        AppUser user = user(10L, Role.PLAYER);
        when(userService.getUser(10L)).thenReturn(user);
        when(userService.belongsToActiveTeam(10L)).thenReturn(true);

        assertThatThrownBy(() -> roleService.removeRole(ADMIN, 10L, Role.PLAYER))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El usuario pertenece a un equipo activo; retírelo del equipo "
                        + "o inactive el equipo antes de quitarle el rol de jugador.");
        assertThat(user.getRoles()).containsExactly(Role.PLAYER);
        verify(auditService, never()).record(any(), any(), anyString(), anyLong(), any());
    }

    @Test
    void playerCanBeRevokedFromAUserWithoutAnActiveTeam() {
        when(userService.getUser(10L)).thenReturn(user(10L, Role.PLAYER, Role.REFEREE));
        when(userService.belongsToActiveTeam(10L)).thenReturn(false);

        assertThat(roleService.removeRole(ADMIN, 10L, Role.PLAYER)).containsExactly(Role.REFEREE);
        verify(auditService).record(eq(ADMIN.id()), eq(AuditAction.ROLE_REMOVED), anyString(), eq(10L), any());
    }

    @Test
    void otherRolesOfALockedPlayerCanStillBeRemoved() {
        when(userService.getUser(10L)).thenReturn(user(10L, Role.PLAYER, Role.REFEREE));

        assertThat(roleService.removeRole(ADMIN, 10L, Role.REFEREE)).containsExactly(Role.PLAYER);
        verify(userService, never()).isLockedByTournament(anyLong());
    }

    @Test
    void rolesCannotBeChangedOnInactiveUsers() {
        AppUser inactive = user(10L, Role.PLAYER);
        inactive.setStatus(UserStatus.INACTIVE);
        when(userService.getUser(10L)).thenReturn(inactive);

        assertThatThrownBy(() -> roleService.assignRole(ADMIN, 10L, Role.CAPTAIN))
                .isInstanceOf(BusinessRuleException.class);
    }

    // --- CAPTAIN managed by the teams module ---------------------------------------------------

    @Test
    void creatingATeamMakesTheCreatorCaptainAuditedAsThemself() {
        AppUser creator = user(20L, Role.PLAYER);
        when(userService.getUser(20L)).thenReturn(creator);

        roleService.grantCaptainForNewTeam(20L, 5L);

        assertThat(creator.getRoles()).containsExactlyInAnyOrder(Role.PLAYER, Role.CAPTAIN);
        verify(auditService).record(20L, AuditAction.ROLE_ASSIGNED, UserService.ENTITY_TYPE, 20L,
                Map.of("role", "CAPTAIN", "reason", "TEAM_CREATED", "teamId", 5L));
    }

    @Test
    void anExistingCaptainIsNotGrantedTwice() {
        when(userService.getUser(20L)).thenReturn(user(20L, Role.PLAYER, Role.CAPTAIN));

        roleService.grantCaptainForNewTeam(20L, 5L);

        verifyNoInteractions(auditService);
    }

    @Test
    void closingATeamRevokesCaptainAuditedWithTheActor() {
        AppUser captain = user(20L, Role.PLAYER, Role.CAPTAIN);
        when(userService.getUser(20L)).thenReturn(captain);

        roleService.revokeCaptainForClosedTeam(1L, 20L, 5L);

        assertThat(captain.getRoles()).containsExactly(Role.PLAYER);
        verify(auditService).record(1L, AuditAction.ROLE_REMOVED, UserService.ENTITY_TYPE, 20L,
                Map.of("role", "CAPTAIN", "reason", "TEAM_INACTIVATED", "teamId", 5L));
        verify(userService, never()).captainsActiveTeam(anyLong());
    }

    @Test
    void closingATeamWhoseCaptainLostTheRoleDoesNothing() {
        when(userService.getUser(20L)).thenReturn(user(20L, Role.PLAYER));

        roleService.revokeCaptainForClosedTeam(20L, 20L, 5L);

        verifyNoInteractions(auditService);
    }

    private static AppUser user(Long id, Role... roles) {
        return AppUser.builder().id(id).status(UserStatus.ACTIVE).roles(new HashSet<>(Set.of(roles))).build();
    }
}
