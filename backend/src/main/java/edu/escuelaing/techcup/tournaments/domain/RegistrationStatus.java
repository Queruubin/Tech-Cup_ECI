package edu.escuelaing.techcup.tournaments.domain;

import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lifecycle of a team registration, modelled with the same <b>State pattern</b> as
 * {@link TournamentStatus}: only {@code UNDER_REVIEW} has outgoing edges, the other three states
 * are terminal.
 *
 * <pre>
 *   UNDER_REVIEW --approve--&gt; APPROVED
 *                --reject---&gt; REJECTED
 *                --cancel---&gt; CANCELLED
 * </pre>
 */
public enum RegistrationStatus {

    /** Payment receipt submitted, waiting for an organizer decision. */
    UNDER_REVIEW("en revisión") {
        @Override
        public Set<RegistrationStatus> allowedTargets() {
            return EnumSet.of(APPROVED, REJECTED, CANCELLED);
        }
    },

    /**
     * Accepted by an organizer: the team takes part. Until the tournament starts its roster may
     * change only while it stays eligible; once it starts the roster is locked.
     */
    APPROVED("aprobada") {
        @Override
        public Set<RegistrationStatus> allowedTargets() {
            return EnumSet.noneOf(RegistrationStatus.class);
        }
    },

    /** Refused by an organizer; the team may submit a new registration. */
    REJECTED("rechazada") {
        @Override
        public Set<RegistrationStatus> allowedTargets() {
            return EnumSet.noneOf(RegistrationStatus.class);
        }
    },

    /** Withdrawn by the captain; the team may submit a new registration. */
    CANCELLED("cancelada") {
        @Override
        public Set<RegistrationStatus> allowedTargets() {
            return EnumSet.noneOf(RegistrationStatus.class);
        }
    };


    private final String label;

    RegistrationStatus(String label) {
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

    public abstract Set<RegistrationStatus> allowedTargets();

    public boolean canTransitionTo(RegistrationStatus target) {
        return allowedTargets().contains(target);
    }

    /**
     * Validates the edge and returns {@code target}.
     *
     * @throws BusinessRuleException when the transition is not part of the state machine
     */
    public RegistrationStatus transitionTo(RegistrationStatus target) {
        if (!canTransitionTo(target)) {
            throw new BusinessRuleException("Una inscripción en estado «" + label() + "» no puede pasar a «"
                    + target.label() + "» (estados permitidos: " + describeAllowedTargets() + ").");
        }
        return target;
    }

    /** The Spanish names of {@link #allowedTargets()}, or "ninguno" for a terminal state. */
    private String describeAllowedTargets() {
        return allowedTargets().isEmpty()
                ? "ninguno"
                : allowedTargets().stream().map(RegistrationStatus::label).collect(Collectors.joining(", "));
    }

    /** UNDER_REVIEW and APPROVED registrations occupy the team's single live slot per tournament. */
    public boolean isLive() {
        return this == UNDER_REVIEW || this == APPROVED;
    }
}
