package edu.escuelaing.techcup.competition.infrastructure;

import edu.escuelaing.techcup.competition.domain.Lineup;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LineupRepository extends JpaRepository<Lineup, Long> {

    Optional<Lineup> findByMatchIdAndTeamId(Long matchId, Long teamId);

    /** Drops the lineup of a team that no longer plays the match (its players go with it). */
    void deleteByMatchIdAndTeamId(Long matchId, Long teamId);
}
