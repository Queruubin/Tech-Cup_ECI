package edu.escuelaing.techcup.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.identity.domain.Role;
import edu.escuelaing.techcup.identity.domain.UserStatus;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.ForbiddenOperationException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import java.util.HashSet;
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
    private AuditService auditService;
    @InjectMocks
    private RoleService roleService;

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
    void organizerCanGrantCaptainToAPlayer() {
        AppUser player = user(10L, Role.PLAYER);
        when(userService.getUser(10L)).thenReturn(player);

        assertThat(roleService.assignRole(ORGANIZER, 10L, Role.CAPTAIN)).containsExactly(Role.PLAYER, Role.CAPTAIN);
        verify(auditService).record(eq(2L), eq(AuditAction.ROLE_ASSIGNED), anyString(), eq(10L), any());
    }

    @Test
    void captainRequiresPlayerRole() {
        when(userService.getUser(10L)).thenReturn(user(10L, Role.GUEST));

        assertThatThrownBy(() -> roleService.assignRole(ORGANIZER, 10L, Role.CAPTAIN))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Solo un jugador");
    }

    @Test
    void adminCanGrantAnyRole() {
        when(userService.getUser(10L)).thenReturn(user(10L, Role.GUEST));

        assertThat(roleService.assignRole(ADMIN, 10L, Role.ORGANIZER)).contains(Role.ORGANIZER);
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

        assertThatThrownBy(() -> roleService.removeRole(ORGANIZER, 10L, Role.CAPTAIN))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("capitán de un equipo activo");
        verify(auditService, never()).record(any(), any(), anyString(), anyLong(), any());
    }

    @Test
    void captainCanBeRevokedOnceTheTeamIsGone() {
        when(userService.getUser(10L)).thenReturn(user(10L, Role.PLAYER, Role.CAPTAIN));
        when(userService.captainsActiveTeam(10L)).thenReturn(false);

        assertThat(roleService.removeRole(ORGANIZER, 10L, Role.CAPTAIN)).containsExactly(Role.PLAYER);
    }

    @Test
    void rolesCannotBeChangedOnInactiveUsers() {
        AppUser inactive = user(10L, Role.PLAYER);
        inactive.setStatus(UserStatus.INACTIVE);
        when(userService.getUser(10L)).thenReturn(inactive);

        assertThatThrownBy(() -> roleService.assignRole(ADMIN, 10L, Role.CAPTAIN))
                .isInstanceOf(BusinessRuleException.class);
    }

    private static AppUser user(Long id, Role... roles) {
        return AppUser.builder().id(id).status(UserStatus.ACTIVE).roles(new HashSet<>(Set.of(roles))).build();
    }
}
