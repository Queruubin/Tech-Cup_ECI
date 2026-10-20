package edu.escuelaing.techcup.competition.infrastructure;

import edu.escuelaing.techcup.competition.domain.GroupTeam;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupTeamRepository extends JpaRepository<GroupTeam, Long> {

    /** Every assignment of the tournament, groups in order and teams by name inside each group. */
    @Query("""
            SELECT gt FROM GroupTeam gt JOIN FETCH gt.team t JOIN FETCH gt.group g
            WHERE gt.tournamentId = :tournamentId
            ORDER BY g.position ASC, LOWER(t.name) ASC, t.id ASC
            """)
    List<GroupTeam> findByTournament(@Param("tournamentId") Long tournamentId);

    Optional<GroupTeam> findByTournamentIdAndTeamId(Long tournamentId, Long teamId);

    /** Ids of the teams of one group. */
    @Query("SELECT gt.team.id FROM GroupTeam gt WHERE gt.group.id = :groupId ORDER BY gt.id ASC")
    List<Long> findTeamIdsByGroupId(@Param("groupId") Long groupId);

    boolean existsByGroupId(Long groupId);
}
