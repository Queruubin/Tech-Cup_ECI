package edu.escuelaing.techcup.identity.application;

import edu.escuelaing.techcup.shared.config.AppProperties;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Only children may play: the PLAYER role is limited to an inclusive age range in full years,
 * configured with {@code app.player.min-age} / {@code app.player.max-age} (5 to 100 by default).
 * "Today" comes from the application {@link Clock}, so the zone is {@code app.time-zone}.
 *
 * <p>Checked when registering as a player, when an administrator assigns the PLAYER role and
 * when a sport profile is created. GUEST, ORGANIZER, REFEREE and ADMIN have no age limit.
 */
@Component
public class PlayerAgePolicy {

    private final int minAge;
    private final int maxAge;
    private final Clock clock;

    /**
     * The constructor Spring uses. It must be annotated because the class also exposes the
     * value-based one below for tests.
     */
    @Autowired
    public PlayerAgePolicy(AppProperties properties, Clock clock) {
        this(properties.player().minAge(), properties.player().maxAge(), clock);
    }

    /** Directly seedable constructor for tests. */
    public PlayerAgePolicy(int minAge, int maxAge, Clock clock) {
        this.minAge = minAge;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    /** Full years between the birth date and today (a birthday counts from that day on). */
    public int ageOn(LocalDate birthDate) {
        return Period.between(birthDate, LocalDate.now(clock)).getYears();
    }

    /** False as well when the birth date is unknown: an age that cannot be checked is not allowed. */
    public boolean isAllowed(LocalDate birthDate) {
        if (birthDate == null) {
            return false;
        }
        int age = ageOn(birthDate);
        return age >= minAge && age <= maxAge;
    }

    /** @throws BusinessRuleException (409) when the birth date is outside the configured range */
    public void validate(LocalDate birthDate) {
        if (!isAllowed(birthDate)) {
            throw new BusinessRuleException(message());
        }
    }

    public String message() {
        return "Para ser jugador la edad debe estar entre " + minAge + " y " + maxAge + " años.";
    }
}
