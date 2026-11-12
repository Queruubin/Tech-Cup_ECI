package edu.escuelaing.techcup.teams.infrastructure;

import edu.escuelaing.techcup.teams.domain.Team;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Native reads over the tournaments module's tables ({@code registrations}, {@code tournaments}).
 * Both tables exist since Flyway V1; {@code suspended_from_status} since V6. A SUSPENDED tournament
 * counts as what it was suspended from (resume returns exactly there): suspended while IN_PROGRESS
 * it keeps its teams locked, suspended while ACTIVE it has not started yet.
 */
public interface RegistrationLockQuery extends Repository<Team, Long> {

    /** APPROVED in a tournament that has started: IN_PROGRESS, or SUSPENDED from IN_PROGRESS. */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM registrations r
                JOIN tournaments t ON t.id = r.tournament_id
                WHERE r.team_id = :teamId
                  AND r.status = 'APPROVED'
                  AND (t.status = 'IN_PROGRESS'
                       OR (t.status = 'SUSPENDED' AND t.suspended_from_status = 'IN_PROGRESS'))
            )
            """, nativeQuery = true)
    boolean hasApprovedRegistrationInStartedTournament(@Param("teamId") Long teamId);

    /** Names of the tournaments not started yet (ACTIVE, or SUSPENDED from ACTIVE) where the team is APPROVED. */
    @Query(value = """
            SELECT t.name FROM registrations r
            JOIN tournaments t ON t.id = r.tournament_id
            WHERE r.team_id = :teamId
              AND r.status = 'APPROVED'
              AND (t.status = 'ACTIVE'
                   OR (t.status = 'SUSPENDED' AND t.suspended_from_status = 'ACTIVE'))
            ORDER BY t.name
            """, nativeQuery = true)
    List<String> findUpcomingTournamentNames(@Param("teamId") Long teamId);
}
