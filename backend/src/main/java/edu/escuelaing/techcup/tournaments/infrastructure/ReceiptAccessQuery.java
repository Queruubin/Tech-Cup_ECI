package edu.escuelaing.techcup.tournaments.infrastructure;

import edu.escuelaing.techcup.tournaments.domain.Registration;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Native read over the teams module's table used to authorise receipt downloads. */
public interface ReceiptAccessQuery extends Repository<Registration, Long> {

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM teams t WHERE t.id = :teamId AND t.captain_user_id = :userId
            )
            """, nativeQuery = true)
    boolean isCaptainOfTeam(@Param("userId") Long userId, @Param("teamId") Long teamId);
}
