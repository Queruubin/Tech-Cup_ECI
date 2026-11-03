package edu.escuelaing.techcup.competition.application;

import java.util.List;

/**
 * <b>Strategy pattern</b> for turning a list of teams into fixtures. The two phases of the
 * tournament pair teams in completely different ways, and the caller
 * ({@link MatchService}) only has to pick a strategy and persist whatever comes back:
 *
 * <ul>
 *   <li>{@link RoundRobinFixtureStrategy} — the group stage: everybody plays everybody once,
 *       split into matchdays with the circle method.</li>
 *   <li>{@link KnockoutFixtureStrategy} — the bracket: the teams, in bracket order, meet their
 *       neighbour (first against second, third against fourth).</li>
 * </ul>
 *
 * Keeping the two algorithms behind one interface is what makes the fixture generator testable
 * without a database, and adding a third format (two groups, home and away, …) a matter of
 * writing one more implementation.
 */
public interface FixtureStrategy {

    /**
     * One fixture to be created.
     *
     * @param roundNumber 1-based matchday <em>within the generated phase</em>; the caller offsets
     *                    it so round numbers stay unique across the whole tournament
     */
    record Fixture(int roundNumber, Long homeTeamId, Long awayTeamId) {
    }

    /**
     * Pairs the given teams.
     *
     * @param teamIds the teams to pair; the meaning of the order depends on the implementation
     *                (irrelevant for the round robin, bracket order for the knockout)
     * @throws edu.escuelaing.techcup.shared.exception.BusinessRuleException when the list cannot
     *                                                                      produce a valid phase
     */
    List<Fixture> generate(List<Long> teamIds);
}
