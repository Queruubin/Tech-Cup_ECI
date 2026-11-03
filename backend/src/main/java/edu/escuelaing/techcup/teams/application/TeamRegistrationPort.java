package edu.escuelaing.techcup.teams.application;

import java.util.List;

/**
 * What the teams module needs from the tournaments module before a team is deleted: its live
 * registrations block the deletion, and its closed ones (history only) go with it. Expressed as a
 * port so teams never depends on tournaments code (tournaments already depends on teams);
 * implemented in {@code tournaments.infrastructure}.
 */
public interface TeamRegistrationPort {

    /** Names of the tournaments where the team has a registration UNDER_REVIEW or APPROVED. */
    List<String> tournamentsWithLiveRegistrationOf(Long teamId);

    /**
     * Deletes the team's REJECTED and CANCELLED registrations.
     *
     * @return how many rows were deleted and their files (payment receipts and university-form
     *         evidence), for the caller to delete after commit
     */
    DeletedRegistrations deleteClosedRegistrationsOf(Long teamId);

    /** The outcome of {@link #deleteClosedRegistrationsOf}. */
    record DeletedRegistrations(int count, List<String> fileIds) {

        public DeletedRegistrations {
            fileIds = List.copyOf(fileIds);
        }
    }
}
