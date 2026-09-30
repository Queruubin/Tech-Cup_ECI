package edu.escuelaing.techcup.competition.infrastructure;

import edu.escuelaing.techcup.competition.domain.Match;
import edu.escuelaing.techcup.competition.domain.MatchPhase;
import edu.escuelaing.techcup.competition.domain.MatchStatus;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MatchRepository extends JpaRepository<Match, Long> {

    boolean existsByTournamentId(Long tournamentId);

    boolean existsByTournamentIdAndPhaseAndStatus(Long tournamentId, MatchPhase phase, MatchStatus status);

    boolean existsByTournamentIdAndStatus(Long tournamentId, MatchStatus status);

    List<Match> findByTournamentIdOrderByScheduledAtAscIdAsc(Long tournamentId);

    List<Match> findByTournamentIdAndPhaseOrderByIdAsc(Long tournamentId, MatchPhase phase);

    List<Match> findByTournamentIdAndStatusOrderByScheduledAtAscIdAsc(Long tournamentId, MatchStatus status);

    List<Match> findByRefereeIdOrderByScheduledAtAscIdAsc(Long refereeId);

    /** Highest round number already used in the tournament; 0 when there is no match yet. */
    @Query("SELECT COALESCE(MAX(m.roundNumber), 0) FROM Match m WHERE m.tournament.id = :tournamentId")
    int findMaxRoundNumber(@Param("tournamentId") Long tournamentId);

    /** The phases that have at least one match in the tournament, in no particular order. */
    @Query("SELECT DISTINCT m.phase FROM Match m WHERE m.tournament.id = :tournamentId")
    List<MatchPhase> findDistinctPhases(@Param("tournamentId") Long tournamentId);

    /** PLAYED matches of a team, most recent first; backs the "previous match" sanction rule. */
    @Query("""
            SELECT m FROM Match m
            WHERE m.tournament.id = :tournamentId
              AND (m.homeTeam.id = :teamId OR m.awayTeam.id = :teamId)
              AND m.status = edu.escuelaing.techcup.competition.domain.MatchStatus.PLAYED
            ORDER BY m.scheduledAt DESC, m.id DESC
            """)
    List<Match> findPlayedOfTeam(@Param("tournamentId") Long tournamentId, @Param("teamId") Long teamId);

    /** Matches involving a team, in playing order (any status). */
    @Query("""
            SELECT m FROM Match m
            WHERE m.tournament.id = :tournamentId
              AND (m.homeTeam.id = :teamId OR m.awayTeam.id = :teamId)
            ORDER BY m.scheduledAt ASC, m.id ASC
            """)
    List<Match> findOfTeam(@Param("tournamentId") Long tournamentId, @Param("teamId") Long teamId);

    /** Next scheduled matches of a tournament, used by the home page. */
    List<Match> findByTournamentIdAndStatusOrderByScheduledAtAscIdAsc(Long tournamentId, MatchStatus status,
                                                                     Limit limit);
}
