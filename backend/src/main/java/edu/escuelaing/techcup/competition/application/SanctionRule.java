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
 *   <li>they were booked and that booking took their running total of yellow cards in the
 *       tournament to an <b>even number of at least two</b> — the classic "two yellows, one match
 *       off, and again every two bookings".</li>
 * </ul>
 * A red card wins over an accumulation: only one reason is reported per player.
 */
final class SanctionRule {

    /**
     * What the rule needs to know about one player of the team.
     *
     * @param accumulatedYellows total yellow cards of the player in the tournament, counted up to
     *                           and including the previous match
     */
    record PlayerFacts(Long userId, String fullName, boolean sentOff, boolean bookedInPreviousMatch,
                       int accumulatedYellows) {
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
            } else if (player.bookedInPreviousMatch() && reachedAccumulation(player.accumulatedYellows())) {
                sanctions.put(player.userId(), new Sanction(player.userId(), player.fullName(),
                        "Acumuló " + player.accumulatedYellows() + " tarjetas amarillas"));
            }
        }
        return List.copyOf(new ArrayList<>(sanctions.values()));
    }

    /** An even total of at least two bookings triggers a suspension. */
    static boolean reachedAccumulation(int accumulatedYellows) {
        return accumulatedYellows >= 2 && accumulatedYellows % 2 == 0;
    }
}
