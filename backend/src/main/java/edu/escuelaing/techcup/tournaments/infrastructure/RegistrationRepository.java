package edu.escuelaing.techcup.tournaments.infrastructure;

import edu.escuelaing.techcup.tournaments.domain.Registration;
import edu.escuelaing.techcup.tournaments.domain.RegistrationStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RegistrationRepository extends JpaRepository<Registration, Long> {

    long countByTournamentIdAndStatus(Long tournamentId, RegistrationStatus status);

    List<Registration> findByTournamentIdOrderByCreatedAtAscIdAsc(Long tournamentId);

    List<Registration> findByTournamentIdAndStatusOrderByIdAsc(Long tournamentId, RegistrationStatus status);

    /** The live (UNDER_REVIEW or APPROVED) registration of a team for a tournament, if any. */
    Optional<Registration> findFirstByTournamentIdAndTeamIdAndStatusInOrderByIdDesc(
            Long tournamentId, Long teamId, Collection<RegistrationStatus> statuses);

    /** Most recent registration of a team for a tournament, whatever its status. */
    Optional<Registration> findFirstByTournamentIdAndTeamIdOrderByIdDesc(Long tournamentId, Long teamId);

    /** Ids of the teams taking part in the tournament, in registration order. */
    @Query("""
            SELECT r.team.id FROM Registration r
            WHERE r.tournament.id = :tournamentId AND r.status = :status
            ORDER BY r.createdAt ASC, r.id ASC
            """)
    List<Long> findTeamIdsByStatus(@Param("tournamentId") Long tournamentId,
                                   @Param("status") RegistrationStatus status);
}
