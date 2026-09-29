package edu.escuelaing.techcup.tournaments.infrastructure;

import edu.escuelaing.techcup.tournaments.domain.Tournament;
import edu.escuelaing.techcup.tournaments.domain.TournamentStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TournamentRepository extends JpaRepository<Tournament, Long> {

    List<Tournament> findAllByOrderByStartDateDescIdDesc();

    /** Latest live tournament; backs {@code GET /tournaments/current}. */
    Optional<Tournament> findFirstByStatusInOrderByStartDateDescIdDesc(Collection<TournamentStatus> statuses);

    /**
     * Loads the tournament with a database write lock. Use cases that count and then create
     * (approving a registration against {@code maxTeams}, generating a phase once) serialise on
     * this row so two concurrent requests cannot both pass the count.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Tournament t WHERE t.id = :id")
    Optional<Tournament> findByIdForUpdate(@Param("id") Long id);
}
