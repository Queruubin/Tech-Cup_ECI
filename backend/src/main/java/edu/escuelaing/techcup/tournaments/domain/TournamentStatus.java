package edu.escuelaing.techcup.tournaments.domain;

import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lifecycle of a tournament, modelled with the <b>State pattern</b>: every constant is a concrete
 * state that knows which states it may move to, so the transition table lives with the states
 * instead of being scattered across {@code if} chains in the service layer.
 *
 * <pre>
 *   DRAFT --activate--&gt; ACTIVE --start--&gt; IN_PROGRESS --finish--&gt; FINISHED
 *   DRAFT | ACTIVE | IN_PROGRESS --suspend--&gt; SUSPENDED --resume--&gt; the status it was suspended from
 * </pre>
 *
 * FINISHED cannot be suspended: it is history and read-only already; an ADMIN who wants it gone
 * deletes it instead.
 *
 * The service layer keeps the <em>guards</em> that need collaborators (a rulebook is uploaded, at
 * least one venue exists, the start date is today, there are enough approved registrations); this
 * enum owns the <em>shape</em> of the machine and rejects every illegal edge with a message the
 * frontend can show verbatim.
 */
public enum TournamentStatus {

    /** Being prepared: the only status in which the tournament may be edited or deleted. */
    DRAFT("borrador") {
        @Override
        public Set<TournamentStatus> allowedTargets() {
            return EnumSet.of(ACTIVE, SUSPENDED);
        }
    },

    /** Published: teams may register, the fixture does not exist yet. */
    ACTIVE("activo") {
        @Override
        public Set<TournamentStatus> allowedTargets() {
            return EnumSet.of(IN_PROGRESS, SUSPENDED);
        }
    },

    /** Being played: registrations are closed and matches are generated and recorded. */
    IN_PROGRESS("en progreso") {
        @Override
        public Set<TournamentStatus> allowedTargets() {
            return EnumSet.of(FINISHED, SUSPENDED);
        }
    },

    /** Terminal state: nothing else may happen to the tournament. */
    FINISHED("finalizado") {
        @Override
        public Set<TournamentStatus> allowedTargets() {
            return EnumSet.noneOf(TournamentStatus.class);
        }
    },

    /**
     * Put on hold by an ADMIN or ORGANIZER: hidden from the public lists and frozen (nothing about
     * the tournament, its registrations, matches, results or lineups may change). It only goes back
     * to the status it was suspended from, which {@code Tournament} remembers.
     */
    SUSPENDED("suspendido") {
        @Override
        public Set<TournamentStatus> allowedTargets() {
            return EnumSet.of(DRAFT, ACTIVE, IN_PROGRESS);
        }
    };


    private final String label;

    TournamentStatus(String label) {
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

    /** The states reachable from this one in a single transition. */
    public abstract Set<TournamentStatus> allowedTargets();

    public boolean canTransitionTo(TournamentStatus target) {
        return allowedTargets().contains(target);
    }

    /**
     * Validates the edge and returns {@code target}.
     *
     * @throws BusinessRuleException when the transition is not part of the state machine
     */
    public TournamentStatus transitionTo(TournamentStatus target) {
        if (!canTransitionTo(target)) {
            throw new BusinessRuleException("Un torneo en estado «" + label() + "» no puede pasar a «"
                    + target.label() + "» (estados permitidos: " + describeAllowedTargets() + ").");
        }
        return target;
    }

    /** The Spanish names of {@link #allowedTargets()}, or "ninguno" for a terminal state. */
    private String describeAllowedTargets() {
        return allowedTargets().isEmpty()
                ? "ninguno"
                : allowedTargets().stream().map(TournamentStatus::label).collect(Collectors.joining(", "));
    }

    /** DRAFT is the only editable and deletable status. */
    public boolean isEditable() {
        return this == DRAFT;
    }

    /**
     * ACTIVE or IN_PROGRESS: the tournament is live. (A SUSPENDED tournament is not live, but its
     * approved teams stay locked as well; see the team-lock queries.)
     */
    public boolean isLive() {
        return this == ACTIVE || this == IN_PROGRESS;
    }

    public boolean isFinished() {
        return this == FINISHED;
    }

    public boolean isSuspended() {
        return this == SUSPENDED;
    }
}
