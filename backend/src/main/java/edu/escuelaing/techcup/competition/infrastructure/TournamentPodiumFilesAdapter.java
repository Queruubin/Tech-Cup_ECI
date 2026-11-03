package edu.escuelaing.techcup.competition.infrastructure;

import edu.escuelaing.techcup.tournaments.application.TournamentFilesPort;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers the tournaments module's {@link TournamentFilesPort}: the podium photos this module
 * attached to a tournament's history, so its hard delete can remove their binaries after commit
 * (the rows go with the tournament through {@code ON DELETE CASCADE}).
 */
@Component
public class TournamentPodiumFilesAdapter implements TournamentFilesPort {

    private final PodiumPhotoRepository podiumPhotos;

    public TournamentPodiumFilesAdapter(PodiumPhotoRepository podiumPhotos) {
        this.podiumPhotos = podiumPhotos;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> fileIdsOf(Long tournamentId) {
        return podiumPhotos.findFileIdsByTournamentId(tournamentId);
    }
}
