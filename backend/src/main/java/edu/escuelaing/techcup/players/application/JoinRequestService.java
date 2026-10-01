package edu.escuelaing.techcup.players.application;

import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.players.api.dto.JoinRequestCreateRequest;
import edu.escuelaing.techcup.players.api.dto.JoinRequestResponse;
import edu.escuelaing.techcup.players.application.TeamGateway.TeamRef;
import edu.escuelaing.techcup.players.domain.JoinRequest;
import edu.escuelaing.techcup.players.domain.JoinRequestDirection;
import edu.escuelaing.techcup.players.domain.JoinRequestStatus;
import edu.escuelaing.techcup.players.domain.PlayerProfile;
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
 * Join requests from players to teams (spec 7.2 "Solicitud de vinculacion"), i.e. the
 * {@code join_requests} rows whose direction is {@link JoinRequestDirection#REQUEST}; the
 * INVITATION rows belong to {@link InvitationService}. Rules enforced here:
 * <ul>
 *   <li>Only a player with a sport profile and no active team can request; at most ONE
 *       PENDING request at a time (pending invitations do not count); the target team must be
 *       ACTIVE, not full and not locked by a tournament (its roster is frozen).</li>
 *   <li>The player may cancel while PENDING; the team captain accepts or rejects. Invitations
 *       are refused by these operations (409).</li>
 *   <li>Accepting locks the player's row, adds the member through the teams module (which
 *       re-validates capacity, jersey uniqueness and "one team per player") and cancels the
 *       player's other pending requests and invitations ({@link #completeMembership}, shared with
 *       invitation acceptance).</li>
 *   <li>When a team is inactivated its pending requests are cancelled on its behalf.</li>
 * </ul>
 * Audited as JOIN_REQUEST_CREATED / CANCELLED / ACCEPTED / REJECTED.
 */
@Service
public class JoinRequestService {

    static final String ENTITY_TYPE = "JOIN_REQUEST";

    private final JoinRequestRepository requests;
    private final PlayerProfileRepository profiles;
    private final UserService userService;
    private final TeamGateway teamGateway;
    private final AuditService auditService;

    public JoinRequestService(JoinRequestRepository requests, PlayerProfileRepository profiles,
                              UserService userService, TeamGateway teamGateway, AuditService auditService) {
        this.requests = requests;
        this.profiles = profiles;
        this.userService = userService;
        this.teamGateway = teamGateway;
        this.auditService = auditService;
    }

    @Transactional
    public JoinRequestResponse create(AuthenticatedUser actor, Long teamId, JoinRequestCreateRequest request) {
        if (!profiles.existsById(actor.id())) {
            throw new BusinessRuleException("Cree su perfil deportivo antes de solicitar el ingreso a un equipo.");
        }
        if (teamGateway.findActiveTeamOf(actor.id()).isPresent()) {
            throw new BusinessRuleException("Usted ya pertenece a un equipo.");
        }
        if (requests.existsByPlayerIdAndDirectionAndStatus(actor.id(), JoinRequestDirection.REQUEST,
                JoinRequestStatus.PENDING)) {
            throw new BusinessRuleException(Constraints.PENDING_JOIN_REQUEST_MESSAGE);
        }
        TeamRef team = teamGateway.getTeam(teamId);
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
        JoinRequest joinRequest;
        try {
            // Flushed now so a concurrent duplicate (double submit) fails here, against the V5
            // unique index, with the same message as the check above.
            joinRequest = requests.saveAndFlush(JoinRequest.builder()
                    .teamId(teamId)
                    .player(userService.getUser(actor.id()))
                    .status(JoinRequestStatus.PENDING)
                    .direction(JoinRequestDirection.REQUEST)
                    .message(request == null || request.message() == null ? null : request.message().trim())
                    .build());
        } catch (DataIntegrityViolationException ex) {
            if (DataIntegrity.violates(ex, Constraints.PENDING_JOIN_REQUEST)) {
                throw new BusinessRuleException(Constraints.PENDING_JOIN_REQUEST_MESSAGE);
            }
            throw ex;
        }
        auditService.record(actor.id(), AuditAction.JOIN_REQUEST_CREATED, ENTITY_TYPE, joinRequest.getId(),
                Map.of("teamId", teamId));
        return toResponse(joinRequest, team);
    }

    @Transactional
    public JoinRequestResponse cancel(AuthenticatedUser actor, Long requestId) {
        JoinRequest joinRequest = requireRequest(requestId);
        if (!joinRequest.getPlayer().getId().equals(actor.id())) {
            throw new ForbiddenOperationException("Solo puede cancelar sus propias solicitudes de vinculación.");
        }
        joinRequest.cancel();
        auditService.record(actor.id(), AuditAction.JOIN_REQUEST_CANCELLED, ENTITY_TYPE, requestId);
        return toResponse(joinRequest);
    }

    @Transactional(readOnly = true)
    public List<JoinRequestResponse> listMine(AuthenticatedUser actor) {
        return requests.findByPlayerIdAndDirectionOrderByCreatedAtDesc(actor.id(), JoinRequestDirection.REQUEST)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<JoinRequestResponse> listForTeam(AuthenticatedUser actor, Long teamId, JoinRequestStatus status) {
        TeamRef team = requireCaptainOrAdmin(actor, teamId);
        List<JoinRequest> found = status == null
                ? requests.findByTeamIdAndDirectionOrderByCreatedAtDesc(teamId, JoinRequestDirection.REQUEST)
                : requests.findByTeamIdAndDirectionAndStatusOrderByCreatedAtDesc(teamId,
                        JoinRequestDirection.REQUEST, status);
        return found.stream().map(r -> toResponse(r, team)).toList();
    }

    /**
     * Accepts the request. The player's user row is locked first so that two captains accepting
     * requests from the same player at the same time are serialised: the second one then sees the
     * membership created by the first and is refused by the teams module.
     */
    @Transactional
    public JoinRequestResponse accept(AuthenticatedUser actor, Long requestId) {
        JoinRequest joinRequest = requireRequest(requestId);
        TeamRef team = requireCaptainOrAdmin(actor, joinRequest.getTeamId());
        Long playerId = completeMembership(joinRequest, team);
        auditService.record(actor.id(), AuditAction.JOIN_REQUEST_ACCEPTED, ENTITY_TYPE, requestId,
                Map.of("teamId", team.id(), "playerId", playerId));
        return toResponse(joinRequest, team);
    }

    @Transactional
    public JoinRequestResponse reject(AuthenticatedUser actor, Long requestId) {
        JoinRequest joinRequest = requireRequest(requestId);
        TeamRef team = requireCaptainOrAdmin(actor, joinRequest.getTeamId());
        joinRequest.reject();
        auditService.record(actor.id(), AuditAction.JOIN_REQUEST_REJECTED, ENTITY_TYPE, requestId,
                Map.of("teamId", team.id(), "playerId", joinRequest.getPlayer().getId()));
        return toResponse(joinRequest, team);
    }

    /**
     * Accepts a PENDING request or invitation and turns it into a membership. The player's user
     * row is locked first so that two acceptances for the same player at the same time are
     * serialised: the second one then sees the membership created by the first and is refused by
     * the teams module (which also re-checks capacity, jersey uniqueness and the sport profile).
     * Finally every other PENDING request and invitation of the player is cancelled.
     *
     * @return the id of the player who joined the team
     */
    @Transactional
    public Long completeMembership(JoinRequest joinRequest, TeamRef team) {
        joinRequest.accept();
        Long playerId = userService.getUserForUpdate(joinRequest.getPlayer().getId()).getId();
        teamGateway.addMember(team.id(), playerId);
        requests.findByPlayerIdAndStatus(playerId, JoinRequestStatus.PENDING).forEach(JoinRequest::cancel);
        return playerId;
    }

    private JoinRequest requireRequest(Long id) {
        JoinRequest joinRequest = requests.findById(id)
                .orElseThrow(() -> NotFoundException.of("la solicitud de vinculación", id));
        if (joinRequest.isInvitation()) {
            throw new BusinessRuleException("El registro " + id + " es una invitación de un equipo; "
                    + "gestiónelo desde las invitaciones.");
        }
        return joinRequest;
    }

    /**
     * The team, provided the actor is its captain or an ADMIN. Public (not package-private) like
     * the other helpers shared with {@link InvitationService}, because calls between the two
     * services go through Spring's class-based proxies.
     *
     * @throws ForbiddenOperationException otherwise
     */
    public TeamRef requireCaptainOrAdmin(AuthenticatedUser actor, Long teamId) {
        TeamRef team = teamGateway.getTeam(teamId);
        if (!actor.isAdmin() && !team.isCaptain(actor.id())) {
            throw new ForbiddenOperationException("Solo el capitán del equipo '" + team.name() + "' puede realizar esta acción.");
        }
        return team;
    }

    public JoinRequestResponse toResponse(JoinRequest joinRequest) {
        return toResponse(joinRequest, teamGateway.getTeam(joinRequest.getTeamId()));
    }

    public JoinRequestResponse toResponse(JoinRequest joinRequest, TeamRef team) {
        PlayerProfile profile = profiles.findById(joinRequest.getPlayer().getId()).orElse(null);
        return new JoinRequestResponse(
                joinRequest.getId(),
                team.id(),
                team.name(),
                joinRequest.getPlayer().getId(),
                joinRequest.getPlayer().getFullName(),
                profile == null ? null : profile.getPosition(),
                profile == null ? null : profile.getJerseyNumber(),
                joinRequest.getStatus(),
                joinRequest.getDirection(),
                joinRequest.getMessage(),
                joinRequest.getCreatedAt());
    }
}
