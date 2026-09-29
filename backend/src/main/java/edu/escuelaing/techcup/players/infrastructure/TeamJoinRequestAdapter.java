package edu.escuelaing.techcup.players.infrastructure;

import edu.escuelaing.techcup.players.domain.JoinRequest;
import edu.escuelaing.techcup.players.domain.JoinRequestStatus;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.teams.application.TeamJoinRequestPort;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exposes the join-request clean-up of the players module to the teams module through its port.
 *
 * <p>Works directly on the repository instead of {@code JoinRequestService}: that service depends
 * on the teams module (through {@code TeamGateway}) and the teams module depends on this port,
 * so routing the call through the service would close a circular bean dependency.
 */
@Component
public class TeamJoinRequestAdapter implements TeamJoinRequestPort {

    static final String ENTITY_TYPE = "JOIN_REQUEST";

    private final JoinRequestRepository requests;
    private final AuditService auditService;

    public TeamJoinRequestAdapter(JoinRequestRepository requests, AuditService auditService) {
        this.requests = requests;
        this.auditService = auditService;
    }

    @Override
    @Transactional
    public int cancelPendingRequestsOf(Long actorUserId, Long teamId) {
        List<JoinRequest> pending =
                requests.findByTeamIdAndStatusOrderByCreatedAtDesc(teamId, JoinRequestStatus.PENDING);
        for (JoinRequest joinRequest : pending) {
            joinRequest.cancel();
            auditService.record(actorUserId, AuditAction.JOIN_REQUEST_CANCELLED, ENTITY_TYPE, joinRequest.getId(),
                    Map.of("teamId", teamId, "reason", "TEAM_INACTIVATED"));
        }
        return pending.size();
    }
}
