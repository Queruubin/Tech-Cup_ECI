package edu.escuelaing.techcup.players.application;

import java.util.List;
import java.util.Optional;

/**
 * What the players module needs to know (and ask) about teams, expressed as a port so this
 * module never depends on the teams module's code. Implemented in {@code teams.infrastructure}.
 */
public interface TeamGateway {

    /** A read model of a team, sufficient for join-request decisions and responses. */
    record TeamRef(Long id, String name, boolean active, Long captainId, int memberCount, int maxMembers) {

        public boolean isFull() {
            return memberCount >= maxMembers;
        }

        public boolean isCaptain(Long userId) {
            return captainId.equals(userId);
        }
    }

    /** The ACTIVE team the user is a member of, if any. */
    Optional<TeamRef> findActiveTeamOf(Long userId);

    /** @throws edu.escuelaing.techcup.shared.exception.NotFoundException when the team does not exist */
    TeamRef getTeam(Long teamId);

    /**
     * Whether the team is locked: registered (APPROVED) in an ACTIVE, IN_PROGRESS or SUSPENDED
     * tournament, so its roster is frozen and it cannot take new players.
     */
    boolean isLocked(Long teamId);

    /**
     * Locks the team row (until the caller's transaction ends) and returns the ids of its members.
     * A member changing their jersey takes it, so the change serialises with teammates changing
     * theirs and with the registration approval that would lock the team.
     *
     * @throws edu.escuelaing.techcup.shared.exception.NotFoundException when the team does not exist
     */
    List<Long> lockMemberIds(Long teamId);

    /**
     * Adds a player to a team enforcing the team rules (active team, capacity, unique jersey,
     * player not in another team, player has a profile).
     *
     * @throws edu.escuelaing.techcup.shared.exception.BusinessRuleException when a rule is violated
     */
    void addMember(Long teamId, Long userId);
}
