package edu.escuelaing.techcup.identity.infrastructure;

import edu.escuelaing.techcup.identity.application.UserFactsPort;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Default {@link UserFactsPort} adapter backed by {@link UserFactsQuery}. */
@Component
public class UserFactsAdapter implements UserFactsPort {

    private final UserFactsQuery query;

    public UserFactsAdapter(UserFactsQuery query) {
        this.query = query;
    }

    @Override
    public boolean hasSportProfile(Long userId) {
        return query.hasSportProfile(userId);
    }

    @Override
    public Optional<Long> activeTeamIdOf(Long userId) {
        return query.findActiveTeamId(userId);
    }

    @Override
    public boolean isLockedByTournament(Long userId) {
        return query.isLockedByTournament(userId);
    }

    @Override
    public boolean captainsActiveTeam(Long userId) {
        return query.captainsActiveTeam(userId);
    }
}
