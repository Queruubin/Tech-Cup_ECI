package edu.escuelaing.techcup.identity.application;

import java.util.Optional;

/**
 * Facts about a user that live in other modules (sport profile, team membership, tournament
 * registrations). Expressed as a port so identity does not depend on those modules.
 */
public interface UserFactsPort {

    boolean hasSportProfile(Long userId);

    Optional<Long> activeTeamIdOf(Long userId);

    /**
     * True when the user belongs to a team with an APPROVED registration in a tournament that has
     * started: IN_PROGRESS, or SUSPENDED from IN_PROGRESS (the team's roster is frozen).
     */
    boolean isLockedByTournament(Long userId);

    /**
     * True when the user belongs to a team with an APPROVED registration in a tournament that has
     * not finished (ACTIVE, IN_PROGRESS or SUSPENDED), whether it has started or not.
     */
    boolean belongsToRegisteredTeam(Long userId);

    /** True when the user is the captain of a team whose status is ACTIVE. */
    boolean captainsActiveTeam(Long userId);
}
