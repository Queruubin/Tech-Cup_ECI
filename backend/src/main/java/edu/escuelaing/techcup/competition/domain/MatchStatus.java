package edu.escuelaing.techcup.competition.domain;

import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lifecycle of a match, modelled with the same <b>State pattern</b> as
 * {@code TournamentStatus}: a scheduled match is either played or cancelled. While the tournament
 * is in progress an organizer may <em>reopen</em> a played or cancelled match, which sends it back
 * to SCHEDULED so any mistake can be fixed (a PLAYED result may also be corrected in place by
 * re-recording it).
 *
 * <pre>
 *   SCHEDULED --result--&gt; PLAYED    --reopen--&gt; SCHEDULED
 *             --cancel--&gt; CANCELLED --reopen--&gt; SCHEDULED
 * </pre>
 */
public enum MatchStatus {

    /** Fixture created, waiting to be played. */
    SCHEDULED("programado") {
        @Override
        public Set<MatchStatus> allowedTargets() {
            return EnumSet.of(PLAYED, CANCELLED);
        }
    },

    /** Result recorded; the match counts for the standings and the statistics. */
    PLAYED("jugado") {
        @Override
        public Set<MatchStatus> allowedTargets() {
            return EnumSet.of(SCHEDULED);
        }
    },

    /** Soft-deleted with a reason (disqualification or no-show); ignored by the standings. */
    CANCELLED("cancelado") {
        @Override
        public Set<MatchStatus> allowedTargets() {
            return EnumSet.of(SCHEDULED);
        }
    };


    private final String label;

    MatchStatus(String label) {
        this.label = label;
    }

    /**
     * Spanish name of this state, for the user-facing messages the frontend shows verbatim.
     * Every enum that reaches such a message exposes the same {@code label()} accessor, so the
     * wording lives with the constant instead of in a switch somewhere in the service layer.
     */
    public String label() {
        return label;
    }

    public abstract Set<MatchStatus> allowedTargets();

    public boolean canTransitionTo(MatchStatus target) {
        return allowedTargets().contains(target);
    }

    /**
     * Validates the edge and returns {@code target}.
     *
     * @throws BusinessRuleException when the transition is not part of the state machine
     */
    public MatchStatus transitionTo(MatchStatus target) {
        if (!canTransitionTo(target)) {
            throw new BusinessRuleException("Un partido en estado «" + label() + "» no puede pasar a «"
                    + target.label() + "» (estados permitidos: " + describeAllowedTargets() + ").");
        }
        return target;
    }

    /** The Spanish names of {@link #allowedTargets()}, or "ninguno" when the state has no way out. */
    private String describeAllowedTargets() {
        return allowedTargets().isEmpty()
                ? "ninguno"
                : allowedTargets().stream().map(MatchStatus::label).collect(Collectors.joining(", "));
    }

    /** A phase is complete when every one of its matches is PLAYED or CANCELLED. */
    public boolean isFinished() {
        return this != SCHEDULED;
    }
}
