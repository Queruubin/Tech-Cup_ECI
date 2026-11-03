package edu.escuelaing.techcup.tournaments.application;

import java.util.List;

/**
 * Binaries that other modules attach to a tournament (today the podium photos of its history,
 * owned by the competition module). The hard delete collects them with the tournament's own files
 * (rulebook, venue images, receipts) and deletes them all after commit, while the rows themselves
 * go through {@code ON DELETE CASCADE}. Expressed as a port so tournaments never depends on
 * competition code (competition already depends on tournaments).
 */
public interface TournamentFilesPort {

    /** Every stored file id other modules attached to {@code tournamentId}; empty when none. */
    List<String> fileIdsOf(Long tournamentId);
}
