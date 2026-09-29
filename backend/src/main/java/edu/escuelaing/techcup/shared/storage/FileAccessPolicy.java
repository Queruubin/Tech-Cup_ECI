package edu.escuelaing.techcup.shared.storage;

/**
 * The one fact file downloads need from the domain modules: whether a user captains a team.
 * Expressed as a port so {@code shared} stays free of module dependencies; implemented in
 * {@code tournaments.infrastructure}, the module that owns the receipts.
 */
public interface FileAccessPolicy {

    boolean isCaptainOfTeam(Long userId, Long teamId);
}
