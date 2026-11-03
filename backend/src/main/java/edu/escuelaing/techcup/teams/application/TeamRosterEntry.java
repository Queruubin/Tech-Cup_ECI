package edu.escuelaing.techcup.teams.application;

import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.players.domain.Position;

/**
 * One member of a team with the sport-profile facts other modules need (e.g. the tournament
 * player export). {@code position} and {@code jerseyNumber} are null for a member without profile.
 */
public record TeamRosterEntry(Long teamId, String teamName, AppUser user, boolean captain,
                              Position position, Integer jerseyNumber) {
}
