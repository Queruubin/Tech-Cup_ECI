package edu.escuelaing.techcup.tournaments.infrastructure;

import edu.escuelaing.techcup.shared.storage.FileAccessPolicy;
import org.springframework.stereotype.Component;

/**
 * Answers the {@link FileAccessPolicy} question of the shared file controller ("does this user
 * captain that team?") for payment receipts, which the tournaments module owns. Backed by a
 * native read over the {@code teams} table, like the other cross-module lock queries.
 */
@Component
public class ReceiptAccessAdapter implements FileAccessPolicy {

    private final ReceiptAccessQuery query;

    public ReceiptAccessAdapter(ReceiptAccessQuery query) {
        this.query = query;
    }

    @Override
    public boolean isCaptainOfTeam(Long userId, Long teamId) {
        return userId != null && teamId != null && query.isCaptainOfTeam(userId, teamId);
    }
}
