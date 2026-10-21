package edu.escuelaing.techcup.teams.infrastructure;

import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.teams.domain.TeamStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamRepository extends JpaRepository<Team, Long> {

    boolean existsByNameIgnoreCase(String name);

    Optional<Team> findByNameIgnoreCase(String name);

    List<Team> findAllByOrderByNameAsc();

    /** Loads the team with a database write lock (roster changes, registration approval, deletion). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Team t WHERE t.id = :id")
    Optional<Team> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT t FROM Team t JOIN t.members m WHERE m.id.userId = :userId AND t.status = :status")
    Optional<Team> findTeamByMemberAndStatus(@Param("userId") Long userId, @Param("status") TeamStatus status);

    /** The single ACTIVE team a user belongs to (the service guarantees at most one). */
    default Optional<Team> findActiveTeamByMember(Long userId) {
        return findTeamByMemberAndStatus(userId, TeamStatus.ACTIVE);
    }
}
