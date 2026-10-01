package edu.escuelaing.techcup.players.application;

import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.identity.domain.Role;
import edu.escuelaing.techcup.players.api.dto.InvitationCreateRequest;
import edu.escuelaing.techcup.players.api.dto.JoinRequestResponse;
import edu.escuelaing.techcup.players.application.TeamGateway.TeamRef;
import edu.escuelaing.techcup.players.domain.JoinRequest;
import edu.escuelaing.techcup.players.domain.JoinRequestDirection;
import edu.escuelaing.techcup.players.domain.JoinRequestStatus;
import edu.escuelaing.techcup.players.infrastructure.JoinRequestRepository;
import edu.escuelaing.techcup.players.infrastructure.PlayerProfileRepository;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.Constraints;
import edu.escuelaing.techcup.shared.exception.DataIntegrity;
import edu.escuelaing.techcup.shared.exception.ForbiddenOperationException;
import edu.escuelaing.techcup.shared.exception.Messages;
import edu.escuelaing.techcup.shared.exception.NotFoundException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invitations from a team to a player (spec 7.2 "Invitaciones"): the {@code join_requests} rows
 * whose direction is {@link JoinRequestDirection#INVITATION}. Rules enforced here:
 * <ul>
 *   <li>Only the team captain (or an ADMIN) invites; the team must be ACTIVE, not full and not
 *       locked by a tournament (its roster is frozen).</li>
 *   <li>The invited user must be ACTIVE, hold the PLAYER role, have a sport profile and not
 *       belong to an ACTIVE team. A team cannot hold two PENDING invitations for the same
 *       player; a player may hold pending invitations from several teams, and they do not count
 *       towards the one-pending-request rule of {@link JoinRequestService}.</li>
 *   <li>Only the invited player accepts or rejects. Accepting runs exactly the membership checks
 *       and locking of a request acceptance ({@link JoinRequestService#completeMembership}) and
 *       cancels the player's other pending requests and invitations.</li>
 *   <li>The captain (or an ADMIN) may cancel a PENDING invitation. Plain join requests are
 *       refused by these operations (409).</li>
 * </ul>
 * Audited as INVITATION_SENT / ACCEPTED / REJECTED / CANCELLED.
 */
@Service
public class InvitationService {

    static final String ENTITY_TYPE = JoinRequestService.ENTITY_TYPE;

    private final JoinRequestRepository requests;
    private final PlayerProfileRepository profiles;
    private final UserService userService;
    private final TeamGateway teamGateway;
    private final JoinRequestService joinRequestService;
    private final AuditService auditService;

    public InvitationService(JoinRequestRepository requests, PlayerProfileRepository profiles,
                             UserService userService, TeamGateway teamGateway,
                             JoinRequestService joinRequestService, AuditService auditService) {
        this.requests = requests;
        this.profiles = profiles;
        this.userService = userService;
        this.teamGateway = teamGateway;
        this.joinRequestService = joinRequestService;
        this.auditService = auditService;
    }

    @Transactional
    public JoinRequestResponse invite(AuthenticatedUser actor, Long teamId, InvitationCreateRequest request) {
        TeamRef team = joinRequestService.requireCaptainOrAdmin(actor, teamId);
        if (!team.active()) {
            throw new BusinessRuleException("El equipo '" + team.name() + "' no está activo.");
        }
        if (teamGateway.isLocked(teamId)) {
            throw new BusinessRuleException(Messages.frozenRoster(team.name()));
        }
        if (team.isFull()) {
            throw new BusinessRuleException("El equipo '" + team.name() + "' ya está completo ("
                    + Messages.plural(team.maxMembers(), "integrante", "integrantes") + ").");
        }
        AppUser player = userService.getUser(request.playerId());
        if (!player.isActive()) {
            throw new BusinessRuleException("El usuario " + player.getFullName() + " está inactivo.");
        }
        if (!player.hasRole(Role.PLAYER)) {
            throw new BusinessRuleException("Solo se puede invitar a usuarios con el rol de jugador.");
        }
        if (!profiles.existsById(player.getId())) {
            throw new BusinessRuleException(player.getFullName() + " todavía no tiene perfil deportivo.");
        }
        teamGateway.findActiveTeamOf(player.getId()).ifPresent(other -> {
            throw new BusinessRuleException(player.getFullName() + " ya pertenece al equipo '" + other.name() + "'.");
        });
        String alreadyInvited = "El equipo '" + team.name() + "' ya tiene una invitación pendiente para "
                + player.getFullName() + ".";
        if (requests.existsByTeamIdAndPlayerIdAndDirectionAndStatus(teamId, player.getId(),
                JoinRequestDirection.INVITATION, JoinRequestStatus.PENDING)) {
            throw new BusinessRuleException(alreadyInvited);
        }
        JoinRequest invitation;
        try {
            // Flushed now so a concurrent duplicate (double submit) fails here, against the V5
            // unique index, with the same message as the check above.
            invitation = requests.saveAndFlush(JoinRequest.builder()
                    .teamId(teamId)
                    .player(player)
                    .status(JoinRequestStatus.PENDING)
                    .direction(JoinRequestDirection.INVITATION)
                    .message(request.message() == null || request.message().isBlank() ? null : request.message().trim())
                    .build());
        } catch (DataIntegrityViolationException ex) {
            if (DataIntegrity.violates(ex, Constraints.PENDING_INVITATION)) {
                throw new BusinessRuleException(alreadyInvited);
            }
            throw ex;
        }
        auditService.record(actor.id(), AuditAction.INVITATION_SENT, ENTITY_TYPE, invitation.getId(),
                Map.of("teamId", teamId, "playerId", player.getId()));
        return joinRequestService.toResponse(invitation, team);
    }

    /** Invitations sent by a team, newest first, optionally filtered by status. Captain or ADMIN. */
    @Transactional(readOnly = true)
    public List<JoinRequestResponse> listForTeam(AuthenticatedUser actor, Long teamId, JoinRequestStatus status) {
        TeamRef team = joinRequestService.requireCaptainOrAdmin(actor, teamId);
        List<JoinRequest> found = status == null
                ? requests.findByTeamIdAndDirectionOrderByCreatedAtDesc(teamId, JoinRequestDirection.INVITATION)
                : requests.findByTeamIdAndDirectionAndStatusOrderByCreatedAtDesc(teamId,
                        JoinRequestDirection.INVITATION, status);
        return found.stream().map(invitation -> joinRequestService.toResponse(invitation, team)).toList();
    }

    /** Every invitation the current user received, newest first. */
    @Transactional(readOnly = true)
    public List<JoinRequestResponse> listMine(AuthenticatedUser actor) {
        return requests.findByPlayerIdAndDirectionOrderByCreatedAtDesc(actor.id(), JoinRequestDirection.INVITATION)
                .stream().map(joinRequestService::toResponse).toList();
    }

    @Transactional
    public JoinRequestResponse accept(AuthenticatedUser actor, Long invitationId) {
        JoinRequest invitation = requireInvitation(invitationId);
        requireInvitedPlayer(actor, invitation);
        TeamRef team = teamGateway.getTeam(invitation.getTeamId());
        Long playerId = joinRequestService.completeMembership(invitation, team);
        auditService.record(actor.id(), AuditAction.INVITATION_ACCEPTED, ENTITY_TYPE, invitationId,
                Map.of("teamId", team.id(), "playerId", playerId));
        return joinRequestService.toResponse(invitation, team);
    }

    @Transactional
    public JoinRequestResponse reject(AuthenticatedUser actor, Long invitationId) {
        JoinRequest invitation = requireInvitation(invitationId);
        requireInvitedPlayer(actor, invitation);
        invitation.reject();
        auditService.record(actor.id(), AuditAction.INVITATION_REJECTED, ENTITY_TYPE, invitationId,
                Map.of("teamId", invitation.getTeamId(), "playerId", actor.id()));
        return joinRequestService.toResponse(invitation);
    }

    @Transactional
    public JoinRequestResponse cancel(AuthenticatedUser actor, Long invitationId) {
        JoinRequest invitation = requireInvitation(invitationId);
        TeamRef team = joinRequestService.requireCaptainOrAdmin(actor, invitation.getTeamId());
        invitation.cancel();
        auditService.record(actor.id(), AuditAction.INVITATION_CANCELLED, ENTITY_TYPE, invitationId,
                Map.of("teamId", team.id(), "playerId", invitation.getPlayer().getId()));
        return joinRequestService.toResponse(invitation, team);
    }

    private JoinRequest requireInvitation(Long id) {
        JoinRequest invitation = requests.findById(id).orElseThrow(() -> NotFoundException.of("la invitación", id));
        if (!invitation.isInvitation()) {
            throw new BusinessRuleException("El registro " + id + " es una solicitud de vinculación, no una invitación.");
        }
        return invitation;
    }

    private static void requireInvitedPlayer(AuthenticatedUser actor, JoinRequest invitation) {
        if (!invitation.getPlayer().getId().equals(actor.id())) {
            throw new ForbiddenOperationException("Solo el jugador invitado puede responder esta invitación.");
        }
    }
}
