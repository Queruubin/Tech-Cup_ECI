package edu.escuelaing.techcup.competition.infrastructure;

import edu.escuelaing.techcup.competition.domain.SanctionedTeam;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SanctionedTeamRepository extends JpaRepository<SanctionedTeam, Long> {

    /** The sanctioned teams of a tournament, in the order they were recorded. */
    List<SanctionedTeam> findByTournamentIdOrderByCreatedAtAscIdAsc(Long tournamentId);

    boolean existsByTournamentIdAndTeamId(Long tournamentId, Long teamId);

    Optional<SanctionedTeam> findByIdAndTournamentId(Long id, Long tournamentId);
}
