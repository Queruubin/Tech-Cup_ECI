package edu.escuelaing.techcup.shared.exception;

/**
 * Names of the database constraints that back a business rule (see the Flyway migrations), with
 * the sentence shown when one is violated by a concurrent request.
 */
public final class Constraints {

    /** V5: one PENDING request (direction REQUEST) per player. */
    public static final String PENDING_JOIN_REQUEST = "ux_join_requests_pending_request";
    public static final String PENDING_JOIN_REQUEST_MESSAGE = "Usted ya tiene una solicitud de vinculación pendiente.";

    /** V5: one PENDING invitation (direction INVITATION) per team and player. */
    public static final String PENDING_INVITATION = "ux_join_requests_pending_invitation";
    public static final String PENDING_INVITATION_MESSAGE = "El equipo ya tiene una invitación pendiente para este jugador.";

    private Constraints() {
    }
}
