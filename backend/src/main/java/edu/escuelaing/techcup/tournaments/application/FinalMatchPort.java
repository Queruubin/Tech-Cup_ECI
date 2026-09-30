package edu.escuelaing.techcup.tournaments.application;

/**
 * The facts the tournaments module needs from the competition module to decide whether a
 * tournament may be finished: whether the FINAL match has already been played (one of the two
 * ways of finishing early) and whether any match is still SCHEDULED (which blocks finishing,
 * because no result can be recorded once the tournament is FINISHED). Expressed as a port so
 * tournaments never depends on competition code — competition already depends on tournaments,
 * and a direct call back would create a cycle.
 */
public interface FinalMatchPort {

    boolean isFinalMatchPlayed(Long tournamentId);

    /** {@code true} while at least one match of the tournament is still SCHEDULED. */
    boolean hasScheduledMatches(Long tournamentId);
}
