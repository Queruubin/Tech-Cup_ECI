package edu.escuelaing.techcup.teams.application;

/**
 * Whether a team is "locked" by the tournaments module: it has an APPROVED registration in a
 * tournament whose status is ACTIVE or IN_PROGRESS (or SUSPENDED, which may be resumed into either). Locked teams cannot be renamed, lose
 * members or be inactivated. Expressed as a port so teams never depends on tournaments code.
 */
public interface TeamLockPort {

    boolean isLocked(Long teamId);
}
