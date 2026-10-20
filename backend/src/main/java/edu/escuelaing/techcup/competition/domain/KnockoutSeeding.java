package edu.escuelaing.techcup.competition.domain;

import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.Messages;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Pure domain rule: the suggested first knockout round, which the organizer may then edit.
 *
 * <p><b>Qualifiers.</b> With groups, the group winners go through first, then the runners-up, then
 * the third-placed teams, and so on; when a whole "level" does not fit, the best teams of that
 * level go through (points, goal difference, goals for, name — the table's own tie-break). Without
 * groups the top of the general table goes through.</p>
 *
 * <p><b>Seeds and pairings.</b> The qualifiers are seeded in that order (all winners before any
 * runner-up). The top half of the seeds meets the bottom half, the best seed the worst one (the
 * classic fold, as {@code KnockoutFixtureStrategy} does), except that two teams of the same group
 * are kept apart whenever possible: the bottom half is assigned to the top half in the way that
 * first minimises the pairings within a group, then (in quarter-finals) the teams of a group that
 * could meet again in the semi-final, and only then the distance from the fold. With two groups
 * and four qualifiers this yields 1°A–2°B and 1°B–2°A.</p>
 *
 * <p>The pairings come out in bracket order: the winner of the first pairing meets the winner of
 * the last one in the next round, the second meets the second to last, and so on.</p>
 */
public final class KnockoutSeeding {

    /** The bracket sizes the knockout supports: final, semi-finals and quarter-finals. */
    public static final List<Integer> SIZES = List.of(2, 4, 8);

    private static final Comparator<Standings.Row> STRENGTH = Comparator
            .comparingInt(Standings.Row::points).reversed()
            .thenComparing(Comparator.comparingInt(Standings.Row::goalDifference).reversed())
            .thenComparing(Comparator.comparingInt(Standings.Row::goalsFor).reversed())
            .thenComparing(Standings.Row::teamName, String.CASE_INSENSITIVE_ORDER);

    /** The table of one group, rows in standings order. */
    public record GroupStanding(Long groupId, String groupName, List<Standings.Row> rows) {
    }

    /** A qualified team; {@code groupId} is null when it comes from the general table. */
    public record Seed(Long teamId, String teamName, String label, Long groupId) {
    }

    public record Pairing(Seed home, Seed away) {
    }

    /** {@code GROUPS} or {@code GENERAL}, telling where the qualifiers came from. */
    public record Suggestion(String source, List<Pairing> pairings) {
    }

    private KnockoutSeeding() {
    }

    /** The knockout phase a bracket of {@code size} teams starts with. */
    public static MatchPhase phaseFor(int size) {
        return switch (size) {
            case 8 -> MatchPhase.QUARTERFINAL;
            case 4 -> MatchPhase.SEMIFINAL;
            case 2 -> MatchPhase.FINAL;
            default -> throw new IllegalArgumentException("Unsupported knockout size: " + size);
        };
    }

    /**
     * @param size    2, 4 or 8 teams
     * @param groups  the group tables, groups in order (groups without teams are ignored)
     * @param general the general table, used only when no group has a team
     * @throws BusinessRuleException when there are not enough teams for a bracket of {@code size}
     */
    public static Suggestion suggest(int size, List<GroupStanding> groups, List<Standings.Row> general) {
        if (!SIZES.contains(size)) {
            throw new IllegalArgumentException("Unsupported knockout size: " + size);
        }
        List<GroupStanding> withTeams = groups.stream().filter(group -> !group.rows().isEmpty()).toList();
        boolean fromGroups = !withTeams.isEmpty();
        List<Seed> seeds = fromGroups ? seedsFromGroups(size, withTeams) : seedsFromGeneral(size, general);
        return new Suggestion(fromGroups ? "GROUPS" : "GENERAL", pair(seeds));
    }

    private static List<Seed> seedsFromGroups(int size, List<GroupStanding> groups) {
        int available = groups.stream().mapToInt(group -> group.rows().size()).sum();
        if (available < size) {
            throw new BusinessRuleException("Los grupos tienen " + Messages.plural(available, "equipo", "equipos")
                    + " en total; una eliminatoria de "
                    + size + " equipos necesita al menos " + size + ".");
        }
        List<Seed> seeds = new ArrayList<>(size);
        for (int level = 0; seeds.size() < size; level++) {
            int finalLevel = level;
            List<GroupStanding> contributing = groups.stream()
                    .filter(group -> group.rows().size() > finalLevel)
                    .sorted(Comparator.comparing((GroupStanding group) -> group.rows().get(finalLevel), STRENGTH))
                    .toList();
            for (GroupStanding group : contributing) {
                if (seeds.size() == size) {
                    break;
                }
                Standings.Row row = group.rows().get(level);
                seeds.add(new Seed(row.teamId(), row.teamName(), (level + 1) + "° " + group.groupName(),
                        group.groupId()));
            }
        }
        return seeds;
    }

    private static List<Seed> seedsFromGeneral(int size, List<Standings.Row> general) {
        if (general.size() < size) {
            throw new BusinessRuleException("El torneo tiene "
                    + Messages.plural(general.size(), "equipo aprobado", "equipos aprobados") + "; una eliminatoria de "
                    + size + " equipos necesita al menos " + size + ".");
        }
        return general.stream()
                .limit(size)
                .map(row -> new Seed(row.teamId(), row.teamName(), row.position() + "° general", null))
                .toList();
    }

    /** Top half against bottom half, with the assignment of lowest cost (see the class comment). */
    private static List<Pairing> pair(List<Seed> seeds) {
        int half = seeds.size() / 2;
        List<Seed> top = seeds.subList(0, half);
        List<Seed> bottom = seeds.subList(half, seeds.size());

        int[] best = null;
        long bestCost = Long.MAX_VALUE;
        for (int[] assignment : permutations(half)) {
            long cost = cost(top, bottom, assignment);
            if (cost < bestCost) {
                bestCost = cost;
                best = assignment;
            }
        }
        List<Pairing> pairings = new ArrayList<>(half);
        for (int index = 0; index < half; index++) {
            pairings.add(new Pairing(top.get(index), bottom.get(Objects.requireNonNull(best)[index])));
        }
        return List.copyOf(pairings);
    }

    /**
     * 1000 per pairing inside one group, 10 per pair of same-group teams that could meet in the
     * following round (only meaningful with four pairings), 1 per step away from the fold.
     */
    private static long cost(List<Seed> top, List<Seed> bottom, int[] assignment) {
        int half = top.size();
        long cost = 0;
        for (int index = 0; index < half; index++) {
            if (sameGroup(top.get(index), bottom.get(assignment[index]))) {
                cost += 1000;
            }
            cost += Math.abs(assignment[index] - (half - 1 - index));
        }
        if (half >= 4) {
            for (int index = 0; index < half / 2; index++) {
                int partner = half - 1 - index;
                List<Seed> path = List.of(top.get(index), bottom.get(assignment[index]),
                        top.get(partner), bottom.get(assignment[partner]));
                for (int first = 0; first < path.size(); first++) {
                    for (int second = first + 1; second < path.size(); second++) {
                        if (sameGroup(path.get(first), path.get(second))) {
                            cost += 10;
                        }
                    }
                }
            }
        }
        return cost;
    }

    private static boolean sameGroup(Seed first, Seed second) {
        return first.groupId() != null && first.groupId().equals(second.groupId());
    }

    /** Every permutation of {@code 0..n-1}, in lexicographic order (n is at most 4). */
    private static List<int[]> permutations(int n) {
        List<int[]> result = new ArrayList<>();
        permute(new int[n], new boolean[n], 0, result);
        return result;
    }

    private static void permute(int[] current, boolean[] used, int position, List<int[]> result) {
        if (position == current.length) {
            result.add(current.clone());
            return;
        }
        for (int value = 0; value < current.length; value++) {
            if (!used[value]) {
                used[value] = true;
                current[position] = value;
                permute(current, used, position + 1, result);
                used[value] = false;
            }
        }
    }
}
