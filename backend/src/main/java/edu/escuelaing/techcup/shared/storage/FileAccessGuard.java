package edu.escuelaing.techcup.shared.storage;

import edu.escuelaing.techcup.shared.exception.ForbiddenOperationException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.security.Roles;
import org.springframework.stereotype.Component;

/**
 * Who may download a stored file. Payment receipts carry personal banking data, so they are
 * served only to organizers, administrators and the captain of the team that uploaded them.
 * Every other kind (photos, venue images, rulebooks) is visible to any authenticated user.
 */
@Component
public class FileAccessGuard {

    private final FileAccessPolicy policy;

    public FileAccessGuard(FileAccessPolicy policy) {
        this.policy = policy;
    }

    public boolean canDownload(AuthenticatedUser actor, StoredFile file) {
        FileOwner owner = file.owner();
        if (owner == null || !owner.isReceipt()) {
            return true;
        }
        if (actor.isAdmin() || actor.hasRole(Roles.ORGANIZER)) {
            return true;
        }
        return policy.isCaptainOfTeam(actor.id(), owner.ownerId());
    }

    /** @throws ForbiddenOperationException when the actor may not download the file */
    public void requireDownload(AuthenticatedUser actor, StoredFile file) {
        if (!canDownload(actor, file)) {
            throw new ForbiddenOperationException("No tiene permiso para ver este archivo.");
        }
    }
}
