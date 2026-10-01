package edu.escuelaing.techcup.teams.application;

/**
 * What the teams module needs from the players module when a team goes away: its pending join
 * requests must not stay open. Expressed as a port so teams never depends on players code;
 * implemented in {@code players.infrastructure}.
 */
public interface TeamJoinRequestPort {

    /**
     * Cancels every PENDING join request addressed to the team and every PENDING invitation it
     * sent.
     *
     * @param actorUserId the user performing the team operation, recorded as the audit actor
     * @param reason      machine-readable cause stored in the audit details (e.g. TEAM_INACTIVATED)
     * @return how many requests were cancelled
     */
    int cancelPendingRequestsOf(Long actorUserId, Long teamId, String reason);
}
