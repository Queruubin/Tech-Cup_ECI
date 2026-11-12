package edu.escuelaing.techcup.teams.application;

import java.util.List;

/**
 * What the teams module needs to know about a team's tournament registrations, expressed as a
 * port so teams never depends on tournaments code. A SUSPENDED tournament counts as the status it
 * was suspended from.
 * <ul>
 *   <li>{@link #isLocked}: the team has an APPROVED registration in a tournament that has started
 *       (IN_PROGRESS). Its roster is frozen until the tournament finishes: nobody joins, leaves or
 *       is removed, the team cannot be renamed or inactivated and its members keep their jersey
 *       numbers and their eligibility data.</li>
 *   <li>{@link #upcomingTournamentsOf}: the tournaments that have not started yet (ACTIVE) where
 *       the team is APPROVED. Its roster may still change, but every change must keep the team
 *       eligible ({@code TeamEligibility}), so it never needs a second approval; it cannot be
 *       inactivated (an organizer removes it from the tournament first).</li>
 * </ul>
 */
public interface TeamLockPort {

    boolean isLocked(Long teamId);

    /** Names (sorted) of the not-started tournaments where the team is APPROVED; empty when none. */
    List<String> upcomingTournamentsOf(Long teamId);
}
