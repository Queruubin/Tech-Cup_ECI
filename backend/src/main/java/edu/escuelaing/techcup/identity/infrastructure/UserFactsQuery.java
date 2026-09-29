package edu.escuelaing.techcup.identity.infrastructure;

import edu.escuelaing.techcup.identity.domain.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Read-only native queries over tables owned by other modules (players, teams, tournaments).
 * Querying the tables directly keeps the identity module free of compile-time dependencies on
 * those modules; the schema is the shared contract (Flyway V1).
 */
public interface UserFactsQuery extends Repository<AppUser, Long> {

    @Query(value = "SELECT EXISTS (SELECT 1 FROM player_profiles p WHERE p.user_id = :userId)",
            nativeQuery = true)
    boolean hasSportProfile(@Param("userId") Long userId);

    @Query(value = """
            SELECT tm.team_id FROM team_members tm
            JOIN teams t ON t.id = tm.team_id
            WHERE tm.user_id = :userId AND t.status = 'ACTIVE'
            LIMIT 1
            """, nativeQuery = true)
    Optional<Long> findActiveTeamId(@Param("userId") Long userId);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM team_members tm
                JOIN registrations r ON r.team_id = tm.team_id
                JOIN tournaments t ON t.id = r.tournament_id
                WHERE tm.user_id = :userId
                  AND r.status = 'APPROVED'
                  AND t.status IN ('ACTIVE', 'IN_PROGRESS')
            )
            """, nativeQuery = true)
    boolean isLockedByTournament(@Param("userId") Long userId);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM teams t WHERE t.captain_user_id = :userId AND t.status = 'ACTIVE'
            )
            """, nativeQuery = true)
    boolean captainsActiveTeam(@Param("userId") Long userId);
}
