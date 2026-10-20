package edu.escuelaing.techcup.teams.infrastructure;

import edu.escuelaing.techcup.teams.domain.Team;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Native read over the tournaments module's tables ({@code registrations}, {@code tournaments}).
 * Both tables exist since Flyway V1; the tournaments module (phase 2) populates them. A SUSPENDED
 * tournament keeps its teams locked (conservative: it may be resumed into ACTIVE or IN_PROGRESS).
 */
public interface RegistrationLockQuery extends Repository<Team, Long> {

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM registrations r
                JOIN tournaments t ON t.id = r.tournament_id
                WHERE r.team_id = :teamId
                  AND r.status = 'APPROVED'
                  AND t.status IN ('ACTIVE', 'IN_PROGRESS', 'SUSPENDED')
            )
            """, nativeQuery = true)
    boolean hasApprovedRegistrationInLiveTournament(@Param("teamId") Long teamId);
}
