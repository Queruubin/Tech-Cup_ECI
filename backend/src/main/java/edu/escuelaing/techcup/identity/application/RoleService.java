package edu.escuelaing.techcup.identity.application;

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
 *   <li>Only an ADMIN assigns or removes roles by hand, and never their own ADMIN role.</li>
 *   <li>PLAYER can only be assigned to a user whose age is inside the configured range
 *       ({@link PlayerAgePolicy}).</li>
 *   <li>CAPTAIN can only be granted to a PLAYER, since a captain plays in the team, and cannot
 *       be revoked by hand while the user still captains an ACTIVE team (the team would be
 *       headless).</li>
 *   <li>PLAYER cannot be revoked by hand while the user captains or belongs to an ACTIVE team
 *       (the roster would hold someone who is no longer a player), mirroring the CAPTAIN rule.</li>
 *   <li>PLAYER and CAPTAIN cannot be removed while the user plays a tournament with their team
 *       (APPROVED registration in an ACTIVE or IN_PROGRESS tournament).</li>
 *   <li>Roles can only be changed by hand on ACTIVE users.</li>
 *   <li>CAPTAIN is otherwise managed by the teams module: granted to whoever creates a team
 *       ({@link #grantCaptainForNewTeam}) and revoked when that team is inactivated
 *       ({@link #revokeCaptainForClosedTeam}).</li>
 * </ul>
 * Audited as ROLE_ASSIGNED / ROLE_REMOVED.
 *
 * <p>Dependencies stay inside identity: the teams module calls this service, never the reverse.
 */
@Service
public class RoleService {

    static final String TEAM_CREATED = "TEAM_CREATED";
    static final String TEAM_INACTIVATED = "TEAM_INACTIVATED";
    static final String PLAYER_IN_ACTIVE_TEAM = "El usuario pertenece a un equipo activo; retírelo del equipo "
            + "o inactive el equipo antes de quitarle el rol de jugador.";
    static final String LOCKED_PLAYER_ROLES = "El usuario está inscrito con su equipo en un torneo activo o en curso; "
            + "no se pueden cambiar sus roles de jugador o capitán hasta que el torneo finalice.";

    private final UserService userService;
    private final PlayerAgePolicy playerAgePolicy;
    private final AuditService auditService;

    public RoleService(UserService userService, PlayerAgePolicy playerAgePolicy, AuditService auditService) {
        this.userService = userService;
        this.playerAgePolicy = playerAgePolicy;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<Role> listRoles(Long userId) {
        return userService.getUser(userId).getRoles().stream().sorted().toList();
    }

    @Transactional
    public List<Role> assignRole(AuthenticatedUser actor, Long userId, Role role) {
        requireAdmin(actor);
        AppUser user = requireActiveUser(userId);
        if (role == Role.CAPTAIN && !user.hasRole(Role.PLAYER)) {
            throw new BusinessRuleException("Solo un jugador puede ser promovido a capitán.");
        }
        if (role == Role.PLAYER && !user.hasRole(Role.PLAYER)) {
            playerAgePolicy.validate(user.getBirthDate());
        }
        if (user.getRoles().add(role)) {
            auditService.record(actor.id(), AuditAction.ROLE_ASSIGNED, UserService.ENTITY_TYPE, userId,
                    Map.of("role", role.name()));
        }
        return user.getRoles().stream().sorted().toList();
    }

    @Transactional
    public List<Role> removeRole(AuthenticatedUser actor, Long userId, Role role) {
        requireAdmin(actor);
        if (role == Role.ADMIN && actor.id().equals(userId)) {
            throw new BusinessRuleException("No puede quitarse a sí mismo el rol de administrador.");
        }
        AppUser user = requireActiveUser(userId);
        if ((role == Role.PLAYER || role == Role.CAPTAIN) && userService.isLockedByTournament(userId)) {
            throw new BusinessRuleException(LOCKED_PLAYER_ROLES);
        }
        if (role == Role.CAPTAIN && userService.captainsActiveTeam(userId)) {
            throw new BusinessRuleException("El usuario es capitán de un equipo activo; "
                    + "inactive el equipo antes de quitarle el rol de capitán.");
        }
        if (role == Role.PLAYER && user.hasRole(Role.PLAYER) && userService.belongsToActiveTeam(userId)) {
            throw new BusinessRuleException(PLAYER_IN_ACTIVE_TEAM);
        }
        if (!user.getRoles().remove(role)) {
            throw new NotFoundException("El usuario " + userId + " no tiene el rol de " + role.label() + ".");
        }
        auditService.record(actor.id(), AuditAction.ROLE_REMOVED, UserService.ENTITY_TYPE, userId,
                Map.of("role", role.name()));
        return user.getRoles().stream().sorted().toList();
    }

    /**
     * Makes the creator of a new team its captain. Internal: called by the teams module inside the
     * team-creation transaction, so it is audited with the creator as actor and needs no
     * administrator. Does nothing when the user already holds CAPTAIN.
     */
    @Transactional
    public void grantCaptainForNewTeam(Long userId, Long teamId) {
        AppUser user = userService.getUser(userId);
        if (user.getRoles().add(Role.CAPTAIN)) {
            auditService.record(userId, AuditAction.ROLE_ASSIGNED, UserService.ENTITY_TYPE, userId,
                    Map.of("role", Role.CAPTAIN.name(), "reason", TEAM_CREATED, "teamId", teamId));
        }
    }

    /**
     * Takes CAPTAIN away from the captain of a team that was just inactivated. Internal: called by
     * the teams module after the team became INACTIVE, in the same transaction. It deliberately
     * skips the "captains an ACTIVE team" guard of {@link #removeRole}: a user belongs to at most
     * one ACTIVE team and the captain is a member, so the team being closed was the only one, and
     * the database may not see its new status until the transaction flushes. Does nothing when
     * the user no longer holds CAPTAIN.
     *
     * @param actorUserId who inactivated the team (its captain or an ADMIN), recorded as audit actor
     */
    @Transactional
    public void revokeCaptainForClosedTeam(Long actorUserId, Long userId, Long teamId) {
        AppUser user = userService.getUser(userId);
        if (user.getRoles().remove(Role.CAPTAIN)) {
            auditService.record(actorUserId, AuditAction.ROLE_REMOVED, UserService.ENTITY_TYPE, userId,
                    Map.of("role", Role.CAPTAIN.name(), "reason", TEAM_INACTIVATED, "teamId", teamId));
        }
    }

    private static void requireAdmin(AuthenticatedUser actor) {
        if (!actor.isAdmin()) {
            throw new ForbiddenOperationException("Solo un administrador puede cambiar los roles de un usuario.");
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
