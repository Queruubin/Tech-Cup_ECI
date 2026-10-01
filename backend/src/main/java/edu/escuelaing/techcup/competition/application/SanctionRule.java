package edu.escuelaing.techcup.competition.application;

import edu.escuelaing.techcup.competition.domain.EventType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure rule behind {@code GET /matches/{id}/sanctioned-players} (spec 7.5). A player of a team
 * misses the next match when, <b>in that team's previous played match</b>:
 * <ul>
 *   <li>they were sent off (a {@link EventType#RED_CARD}), or</li>
 *   <li>they were booked and those bookings made their running total of yellow cards in the
 *       tournament <b>cross an even threshold</b> (2, 4, 6...) — the classic "two yellows, one
 *       match off, and again every two bookings". Crossing is what counts, not landing on the even
 *       number: going from 1 to 3 in one match crosses 2 and suspends, going from 2 to 3 does
 *       not.</li>
 * </ul>
 * A red card wins over an accumulation: only one reason is reported per player.
 */
final class SanctionRule {

    /**
     * What the rule needs to know about one player of the team.
     *
     * @param yellowsInPreviousMatch yellow cards the player received in the previous match
     * @param accumulatedYellows     total yellow cards of the player in the tournament, counted up
     *                               to and including the previous match
     */
    record PlayerFacts(Long userId, String fullName, boolean sentOff, int yellowsInPreviousMatch,
                       int accumulatedYellows) {

        boolean bookedInPreviousMatch() {
            return yellowsInPreviousMatch > 0;
        }

        /** The running total before the previous match, never below zero. */
        int yellowsBeforePreviousMatch() {
            return Math.max(0, accumulatedYellows - yellowsInPreviousMatch);
        }
    }

    /** @param reason user-facing Spanish sentence, shown verbatim by the frontend */
    record Sanction(Long userId, String fullName, String reason) {
    }

    private SanctionRule() {
    }

    static List<Sanction> apply(List<PlayerFacts> players) {
        Map<Long, Sanction> sanctions = new LinkedHashMap<>();
        for (PlayerFacts player : players) {
            if (player.sentOff()) {
                sanctions.put(player.userId(),
                        new Sanction(player.userId(), player.fullName(), "Expulsado en el partido anterior"));
            } else if (player.bookedInPreviousMatch()
                    && crossedAccumulation(player.yellowsBeforePreviousMatch(), player.accumulatedYellows())) {
                sanctions.put(player.userId(), new Sanction(player.userId(), player.fullName(),
                        "Acumuló " + player.accumulatedYellows() + " tarjetas amarillas"));
            }
        }
        return List.copyOf(new ArrayList<>(sanctions.values()));
    }

    /**
     * Whether going from {@code before} to {@code after} bookings crossed (or reached) an even
     * threshold of at least two: 1&rarr;2, 1&rarr;3 and 3&rarr;4 do, 2&rarr;3 and 0&rarr;1 do not.
     */
    static boolean crossedAccumulation(int before, int after) {
        return before / 2 < after / 2;
    }
}
