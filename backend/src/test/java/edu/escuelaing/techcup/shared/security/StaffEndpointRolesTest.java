package edu.escuelaing.techcup.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import edu.escuelaing.techcup.competition.api.TournamentCompetitionController;
import edu.escuelaing.techcup.competition.api.TournamentHistoryController;
import edu.escuelaing.techcup.identity.api.AdminUserController;
import edu.escuelaing.techcup.players.api.AdminPlayerPhotoController;
import edu.escuelaing.techcup.tournaments.api.TournamentExportController;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * The role each staff-only endpoint requires at the HTTP boundary (the use cases re-check it).
 * GET /api/tournaments/** is public in the security chain, so the export depends on its
 * {@link PreAuthorize} entirely.
 */
class StaffEndpointRolesTest {

    @Test
    void theStaffUserDetailIsForAdminsAndOrganizers() {
        assertThat(roleOf(AdminUserController.class, "get")).isEqualTo("hasAnyRole('ADMIN', 'ORGANIZER')");
    }

    @Test
    void removingAnotherPlayersPhotoIsForAdminsOnly() {
        assertThat(roleOf(AdminPlayerPhotoController.class, "removePhoto")).isEqualTo("hasRole('ADMIN')");
    }

    @Test
    void thePlayerExportIsForAdminsOnly() {
        assertThat(roleOf(TournamentExportController.class, "exportPlayers")).isEqualTo("hasRole('ADMIN')");
    }

    @Test
    void generatingEveryGroupAndDeletingAGroupMatchAreForOrganizers() {
        assertThat(roleOf(TournamentCompetitionController.class, "generateGroupMatches")).isEqualTo("hasRole('ORGANIZER')");
        assertThat(roleOf(TournamentCompetitionController.class, "deleteMatch")).isEqualTo("hasRole('ORGANIZER')");
    }

    /** The history reads are public; every write is ORGANIZER (ADMIN inherits it through the role hierarchy). */
    @Test
    void theHistoryIsPublicToReadAndManagedByStaff() {
        for (String write : new String[]{"addSanction", "removeSanction", "replacePodiumPhoto", "removePodiumPhoto"}) {
            assertThat(roleOf(TournamentHistoryController.class, write)).isEqualTo("hasRole('ORGANIZER')");
        }
        for (String read : new String[]{"finished", "history", "podiumPhoto"}) {
            Method method = Arrays.stream(TournamentHistoryController.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(read))
                    .findFirst()
                    .orElseThrow();
            assertThat(method.getAnnotation(PreAuthorize.class)).as(read + " is public").isNull();
        }
    }

    private static String roleOf(Class<?> controller, String methodName) {
        Method method = Arrays.stream(controller.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as(controller.getSimpleName() + "." + methodName + " must declare @PreAuthorize").isNotNull();
        return annotation.value();
    }
}
