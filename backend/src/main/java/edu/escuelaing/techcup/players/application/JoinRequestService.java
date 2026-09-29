package edu.escuelaing.techcup.players.application;

import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.players.api.dto.JoinRequestCreateRequest;
import edu.escuelaing.techcup.players.api.dto.JoinRequestResponse;
import edu.escuelaing.techcup.players.application.TeamGateway.TeamRef;
import edu.escuelaing.techcup.players.domain.JoinRequest;
import edu.escuelaing.techcup.players.domain.JoinRequestStatus;
import edu.escuelaing.techcup.players.domain.PlayerProfile;
import edu.escuelaing.techcup.players.infrastructure.JoinRequestRepository;
import edu.escuelaing.techcup.players.infrastructure.PlayerProfileRepository;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.ForbiddenOperationException;
import edu.escuelaing.techcup.shared.exception.Messages;
import edu.escuelaing.techcup.shared.exception.NotFoundException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Join requests from players to teams (spec 7.2 "Solicitud de vinculacion" / "Invitaciones").
 * Rules enforced here:
 * <ul>
 *   <li>Only a player with a sport profile and no active team can request; at most ONE
 *       PENDING request at a time; the target team must be ACTIVE and not full.</li>
 *   <li>The player may cancel while PENDING; the team captain accepts or rejects.</li>
 *   <li>Accepting locks the player's row, adds the member through the teams module (which
 *       re-validates capacity, jersey uniqueness and "one team per player") and cancels the
 *       player's other pending requests.</li>
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
        if (requests.existsByPlayerIdAndStatus(actor.id(), JoinRequestStatus.PENDING)) {
            throw new BusinessRuleException("Usted ya tiene una solicitud de vinculación pendiente.");
        }
        TeamRef team = teamGateway.getTeam(teamId);
        if (!team.active()) {
            throw new BusinessRuleException("El equipo '" + team.name() + "' no está activo.");
        }
        if (team.isFull()) {
            throw new BusinessRuleException("El equipo '" + team.name() + "' ya está completo ("
                    + Messages.plural(team.maxMembers(), "integrante", "integrantes") + ").");
        }
        JoinRequest joinRequest = requests.save(JoinRequest.builder()
                .teamId(teamId)
                .player(userService.getUser(actor.id()))
                .status(JoinRequestStatus.PENDING)
                .message(request == null || request.message() == null ? null : request.message().trim())
                .build());
        auditService.record(actor.id(), AuditAction.JOIN_REQUEST_CREATED, ENTITY_TYPE, joinRequest.getId(),
                Map.of("teamId", teamId));
        return toResponse(joinRequest, team);
    }

    @Transactional
    public JoinRequestResponse cancel(AuthenticatedUser actor, Long requestId) {
        JoinRequest joinRequest = require(requestId);
        if (!joinRequest.getPlayer().getId().equals(actor.id())) {
            throw new ForbiddenOperationException("Solo puede cancelar sus propias solicitudes de vinculación.");
        }
        joinRequest.cancel();
        auditService.record(actor.id(), AuditAction.JOIN_REQUEST_CANCELLED, ENTITY_TYPE, requestId);
        return toResponse(joinRequest);
    }

    @Transactional(readOnly = true)
    public List<JoinRequestResponse> listMine(AuthenticatedUser actor) {
        return requests.findByPlayerIdOrderByCreatedAtDesc(actor.id()).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<JoinRequestResponse> listForTeam(AuthenticatedUser actor, Long teamId, JoinRequestStatus status) {
        TeamRef team = requireCaptainOrAdmin(actor, teamId);
        List<JoinRequest> found = status == null
                ? requests.findByTeamIdOrderByCreatedAtDesc(teamId)
                : requests.findByTeamIdAndStatusOrderByCreatedAtDesc(teamId, status);
        return found.stream().map(r -> toResponse(r, team)).toList();
    }

    /**
     * Accepts the request. The player's user row is locked first so that two captains accepting
     * requests from the same player at the same time are serialised: the second one then sees the
     * membership created by the first and is refused by the teams module.
     */
    @Transactional
    public JoinRequestResponse accept(AuthenticatedUser actor, Long requestId) {
        JoinRequest joinRequest = require(requestId);
        TeamRef team = requireCaptainOrAdmin(actor, joinRequest.getTeamId());
        joinRequest.accept();
        Long playerId = userService.getUserForUpdate(joinRequest.getPlayer().getId()).getId();
        teamGateway.addMember(team.id(), playerId);
        requests.findByPlayerIdAndStatus(playerId, JoinRequestStatus.PENDING).forEach(JoinRequest::cancel);
        auditService.record(actor.id(), AuditAction.JOIN_REQUEST_ACCEPTED, ENTITY_TYPE, requestId,
                Map.of("teamId", team.id(), "playerId", playerId));
        return toResponse(joinRequest, team);
    }

    @Transactional
    public JoinRequestResponse reject(AuthenticatedUser actor, Long requestId) {
        JoinRequest joinRequest = require(requestId);
        TeamRef team = requireCaptainOrAdmin(actor, joinRequest.getTeamId());
        joinRequest.reject();
        auditService.record(actor.id(), AuditAction.JOIN_REQUEST_REJECTED, ENTITY_TYPE, requestId,
                Map.of("teamId", team.id(), "playerId", joinRequest.getPlayer().getId()));
        return toResponse(joinRequest, team);
    }

    private JoinRequest require(Long id) {
        return requests.findById(id).orElseThrow(() -> NotFoundException.of("la solicitud de vinculación", id));
    }

    private TeamRef requireCaptainOrAdmin(AuthenticatedUser actor, Long teamId) {
        TeamRef team = teamGateway.getTeam(teamId);
        if (!actor.isAdmin() && !team.isCaptain(actor.id())) {
            throw new ForbiddenOperationException("Solo el capitán del equipo '" + team.name() + "' puede realizar esta acción.");
        }
        return team;
    }

    private JoinRequestResponse toResponse(JoinRequest joinRequest) {
        return toResponse(joinRequest, teamGateway.getTeam(joinRequest.getTeamId()));
    }

    private JoinRequestResponse toResponse(JoinRequest joinRequest, TeamRef team) {
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
                joinRequest.getMessage(),
                joinRequest.getCreatedAt());
    }
}
