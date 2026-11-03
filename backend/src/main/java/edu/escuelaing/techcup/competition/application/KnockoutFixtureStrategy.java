package edu.escuelaing.techcup.competition.application;

import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Knockout {@link FixtureStrategy}: the teams are listed in <b>bracket order</b> and paired with
 * their neighbour, the first against the second, the third against the fourth, and so on.
 *
 * <pre>
 *   winners of the quarter-finals W1 W2 W3 W4  ->  semi-finals W1vW2  W3vW4
 *   winners of the semi-finals    W1 W2        ->  final       W1vW2
 * </pre>
 *
 * This is how the bracket is drawn and read: match 1 and match 2 feed the first match of the next
 * round, match 3 and match 4 the second one. The strongest seeds are kept apart by the order of
 * the first round instead ({@code KnockoutSeeding} lists the pairings in standard bracket order, so
 * seeds 1 and 2 can only meet in the final). Until 2026-10 the list was folded instead (W1vW4,
 * W2vW3): knockout rounds that existed before the change keep that rule for the round drawn from
 * them ({@link #fold}, {@code Match#isLegacyFold}), and their corrections keep reaching the right
 * slot (see {@code MatchService#planPropagation}).
 */
@Component
public class KnockoutFixtureStrategy implements FixtureStrategy {

    private static final int MIN_TEAMS = 2;

    /**
     * @param teamIds the teams in bracket order (the winners of the previous round, match by match)
     * @return one fixture per neighbouring pair: fixture {@code i} is team {@code 2i} (home) against
     *         team {@code 2i + 1} (away)
     */
    @Override
    public List<Fixture> generate(List<Long> teamIds) {
        int size = requireEvenBracket(teamIds);
        List<Fixture> fixtures = new ArrayList<>(size / 2);
        for (int index = 0; index < size; index += 2) {
            fixtures.add(new Fixture(1, teamIds.get(index), teamIds.get(index + 1)));
        }
        return List.copyOf(fixtures);
    }

    /**
     * The rule used until 2026-10, kept only for rounds drawn from knockout matches created under
     * it: the list folded in half, first against last (W1vW4, W2vW3).
     *
     * @return fixture {@code i} is team {@code i} (home) against team {@code size - 1 - i} (away)
     */
    public List<Fixture> fold(List<Long> teamIds) {
        int size = requireEvenBracket(teamIds);
        List<Fixture> fixtures = new ArrayList<>(size / 2);
        for (int index = 0; index < size / 2; index++) {
            fixtures.add(new Fixture(1, teamIds.get(index), teamIds.get(size - 1 - index)));
        }
        return List.copyOf(fixtures);
    }

    private static int requireEvenBracket(List<Long> teamIds) {
        int size = teamIds.size();
        if (size < MIN_TEAMS) {
            throw new BusinessRuleException("Se requieren al menos " + MIN_TEAMS
                    + " equipos para generar una fase eliminatoria (se recibieron " + size + ").");
        }
        if (size % 2 != 0) {
            throw new BusinessRuleException("Una fase eliminatoria necesita un número par de equipos (se recibieron " + size + ").");
        }
        return size;
    }
}
