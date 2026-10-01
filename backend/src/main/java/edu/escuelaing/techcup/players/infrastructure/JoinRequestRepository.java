package edu.escuelaing.techcup.players.infrastructure;

import edu.escuelaing.techcup.players.domain.JoinRequest;
import edu.escuelaing.techcup.players.domain.JoinRequestDirection;
import edu.escuelaing.techcup.players.domain.JoinRequestStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JoinRequestRepository extends JpaRepository<JoinRequest, Long> {

    boolean existsByPlayerIdAndDirectionAndStatus(Long playerId, JoinRequestDirection direction,
                                                  JoinRequestStatus status);

    boolean existsByTeamIdAndPlayerIdAndDirectionAndStatus(Long teamId, Long playerId, JoinRequestDirection direction,
                                                           JoinRequestStatus status);

    List<JoinRequest> findByPlayerIdAndDirectionOrderByCreatedAtDesc(Long playerId, JoinRequestDirection direction);

    /** Both directions: once a player joins a team, every other pending row of theirs is moot. */
    List<JoinRequest> findByPlayerIdAndStatus(Long playerId, JoinRequestStatus status);

    List<JoinRequest> findByTeamIdAndDirectionOrderByCreatedAtDesc(Long teamId, JoinRequestDirection direction);

    List<JoinRequest> findByTeamIdAndDirectionAndStatusOrderByCreatedAtDesc(Long teamId,
                                                                           JoinRequestDirection direction,
                                                                           JoinRequestStatus status);

    /** Both directions: used when the team is inactivated. */
    List<JoinRequest> findByTeamIdAndStatusOrderByCreatedAtDesc(Long teamId, JoinRequestStatus status);
}
