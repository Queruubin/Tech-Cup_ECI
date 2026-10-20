package edu.escuelaing.techcup.competition.infrastructure;

import edu.escuelaing.techcup.competition.domain.TournamentGroup;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TournamentGroupRepository extends JpaRepository<TournamentGroup, Long> {

    List<TournamentGroup> findByTournamentIdOrderByPositionAsc(Long tournamentId);

    Optional<TournamentGroup> findByIdAndTournamentId(Long id, Long tournamentId);

    boolean existsByTournamentId(Long tournamentId);
}
