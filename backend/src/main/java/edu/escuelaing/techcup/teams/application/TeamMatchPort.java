package edu.escuelaing.techcup.teams.application;

import java.util.List;

/**
 * What the teams module needs from the competition module before a team is deleted: whether it
 * appears in any match. A match row references the team (home, away, events, lineups), so such a
 * team can only go once its tournament is deleted. Expressed as a port so teams never depends on
 * competition code (competition already depends on teams); implemented in
 * {@code competition.infrastructure}.
 */
public interface TeamMatchPort {

    /** Names of the tournaments in which the team plays at least one match, whatever its status. */
    List<String> tournamentsWithMatchesOf(Long teamId);
}
