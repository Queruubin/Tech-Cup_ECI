package edu.escuelaing.techcup.identity;

import static org.assertj.core.api.Assertions.assertThat;

import edu.escuelaing.techcup.competition.api.dto.MatchResponse;
import edu.escuelaing.techcup.competition.api.dto.TournamentHistoryResponse;
import edu.escuelaing.techcup.identity.api.dto.LoginResponse;
import edu.escuelaing.techcup.identity.api.dto.RegisterRequest;
import edu.escuelaing.techcup.identity.api.dto.UpdateUserRequest;
import edu.escuelaing.techcup.identity.api.dto.UserResponse;
import edu.escuelaing.techcup.players.api.dto.JoinRequestResponse;
import edu.escuelaing.techcup.players.api.dto.PlayerProfileResponse;
import edu.escuelaing.techcup.teams.api.dto.TeamResponse;
import edu.escuelaing.techcup.tournaments.application.PlayerExportRow;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

/**
 * The phone is personal data: only {@link UserResponse} may carry it, and that response is built
 * by {@code UserService}, which redacts it per viewer (the user, ADMIN and ORGANIZER only). This
 * guard scans every record of the application: a new DTO with a phone field, or one embedding a
 * {@link UserResponse} (e.g. in a team, player, match or history response), fails here and needs a
 * privacy review before it is allowed.
 */
class PhoneNumberExposureTest {

    private static final String BASE_PACKAGE = "edu.escuelaing.techcup";

    @Test
    void onlyTheUserResponseTheRequestsAndTheAdminExportHaveAPhone() {
        assertThat(recordsMatching(component -> component.getName().toLowerCase(Locale.ROOT).contains("phone")))
                .containsExactlyInAnyOrder(UserResponse.class.getName(), RegisterRequest.class.getName(),
                        UpdateUserRequest.class.getName(), PlayerExportRow.class.getName());
    }

    @Test
    void onlyTheLoginResponseEmbedsAUserResponse() {
        // LoginResponse carries the caller's own user; nothing else may embed one (lists included).
        assertThat(recordsMatching(component -> component.getGenericType().getTypeName()
                .contains(UserResponse.class.getName())))
                .containsExactly(LoginResponse.class.getName());
    }

    @Test
    void teamPlayerAndMatchResponsesHaveNoPhone() {
        for (Class<?> type : Set.of(TeamResponse.class, TeamResponse.Member.class, PlayerProfileResponse.class,
                JoinRequestResponse.class, MatchResponse.class, TournamentHistoryResponse.class)) {
            assertThat(Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName))
                    .as(type.getSimpleName())
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("phone"));
        }
    }

    private static Set<String> recordsMatching(Predicate<RecordComponent> predicate) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Record.class));
        Set<String> found = new TreeSet<>();
        scanner.findCandidateComponents(BASE_PACKAGE).forEach(definition -> {
            Class<?> type = load(definition.getBeanClassName());
            if (type.isRecord() && !type.getName().contains("Test")
                    && Arrays.stream(type.getRecordComponents()).anyMatch(predicate)) {
                found.add(type.getName());
            }
        });
        return found;
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, PhoneNumberExposureTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
