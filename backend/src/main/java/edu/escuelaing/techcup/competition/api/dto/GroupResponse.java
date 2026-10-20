package edu.escuelaing.techcup.competition.api.dto;

import java.util.List;

/**
 * One group of the group stage with its teams (by name). {@code hasMatches} tells whether the team
 * already plays a match of this group, in which case it can no longer be moved or removed;
 * {@code matchCount} counts every match of the group, whatever its status.
 */
public record GroupResponse(Long id, String name, int position, List<Team> teams, int matchCount) {

    public record Team(Long id, String name, String colors, boolean hasMatches) {
    }
}
