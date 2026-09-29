package edu.escuelaing.techcup.identity.application;

import edu.escuelaing.techcup.identity.api.dto.UserResponse;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.identity.domain.Role;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.ForbiddenOperationException;
import edu.escuelaing.techcup.shared.exception.NotFoundException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Role management (spec 7.1 "Autorizacion"). Rules enforced here:
 * <ul>
 *   <li>An ADMIN may assign or remove any role, except their own ADMIN role.</li>
 *   <li>An ORGANIZER may only grant or revoke CAPTAIN; never ADMIN nor ORGANIZER
 *       ("the organizer cannot raise anybody to administrator").</li>
 *   <li>CAPTAIN can only be granted to a PLAYER, since a captain plays in the team, and cannot
 *       be revoked while the user still captains an ACTIVE team (the team would be headless).</li>
 *   <li>Roles can only be changed on ACTIVE users.</li>
 * </ul>
 * Audited as ROLE_ASSIGNED / ROLE_REMOVED.
 */
@Service
public class RoleService {

    private final UserService userService;
    private final AuditService auditService;

    public RoleService(UserService userService, AuditService auditService) {
        this.userService = userService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<Role> listRoles(Long userId) {
        return userService.getUser(userId).getRoles().stream().sorted().toList();
    }

    @Transactional
    public List<Role> assignRole(AuthenticatedUser actor, Long userId, Role role) {
        requireAuthority(actor, role, Action.GRANT);
        AppUser user = requireActiveUser(userId);
        if (role == Role.CAPTAIN && !user.hasRole(Role.PLAYER)) {
            throw new BusinessRuleException("Solo un jugador puede ser promovido a capitán.");
        }
        if (user.getRoles().add(role)) {
            auditService.record(actor.id(), AuditAction.ROLE_ASSIGNED, UserService.ENTITY_TYPE, userId,
                    Map.of("role", role.name()));
        }
        return user.getRoles().stream().sorted().toList();
    }

    @Transactional
    public List<Role> removeRole(AuthenticatedUser actor, Long userId, Role role) {
        requireAuthority(actor, role, Action.REMOVE);
        if (role == Role.ADMIN && actor.id().equals(userId)) {
            throw new BusinessRuleException("No puede quitarse a sí mismo el rol de administrador.");
        }
        AppUser user = requireActiveUser(userId);
        if (role == Role.CAPTAIN && userService.captainsActiveTeam(userId)) {
            throw new BusinessRuleException("El usuario es capitán de un equipo activo; "
                    + "inactive el equipo antes de quitarle el rol de capitán.");
        }
        if (!user.getRoles().remove(role)) {
            throw new NotFoundException("El usuario " + userId + " no tiene el rol de " + role.label() + ".");
        }
        auditService.record(actor.id(), AuditAction.ROLE_REMOVED, UserService.ENTITY_TYPE, userId,
                Map.of("role", role.name()));
        return user.getRoles().stream().sorted().toList();
    }

    @Transactional
    public UserResponse grantCaptain(AuthenticatedUser actor, Long userId) {
        assignRole(actor, userId, Role.CAPTAIN);
        return userService.getResponse(userId, actor);
    }

    @Transactional
    public UserResponse revokeCaptain(AuthenticatedUser actor, Long userId) {
        removeRole(actor, userId, Role.CAPTAIN);
        return userService.getResponse(userId, actor);
    }

    private static void requireAuthority(AuthenticatedUser actor, Role role, Action action) {
        if (!actor.isAdmin() && role != Role.CAPTAIN) {
            throw new ForbiddenOperationException("Un organizador solo puede " + action.label
                    + " el rol de capitán.");
        }
    }

    /** What an organizer is trying to do with a role, so the refusal reads as a Spanish sentence. */
    private enum Action {

        GRANT("asignar"),
        REMOVE("quitar");

        private final String label;

        Action(String label) {
            this.label = label;
        }
    }

    private AppUser requireActiveUser(Long userId) {
        AppUser user = userService.getUser(userId);
        if (!user.isActive()) {
            throw new BusinessRuleException("No se pueden cambiar los roles de un usuario inactivo.");
        }
        return user;
    }
}
