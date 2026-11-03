package edu.escuelaing.techcup.competition.infrastructure;

import edu.escuelaing.techcup.competition.domain.PodiumPhoto;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PodiumPhotoRepository extends JpaRepository<PodiumPhoto, Long> {

    List<PodiumPhoto> findByTournamentIdOrderByPositionAsc(Long tournamentId);

    Optional<PodiumPhoto> findByTournamentIdAndPosition(Long tournamentId, int position);

    /** The GridFS ids of every podium photo of a tournament (collected by its hard delete). */
    @Query("SELECT p.fileId FROM PodiumPhoto p WHERE p.tournamentId = :tournamentId")
    List<String> findFileIdsByTournamentId(@Param("tournamentId") Long tournamentId);
}
