package edu.escuelaing.techcup.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import edu.escuelaing.techcup.competition.api.TournamentCompetitionController;
import edu.escuelaing.techcup.competition.api.TournamentHistoryController;
import edu.escuelaing.techcup.identity.api.AdminUserController;
import edu.escuelaing.techcup.players.api.AdminPlayerPhotoController;
import edu.escuelaing.techcup.teams.api.TeamController;
import edu.escuelaing.techcup.tournaments.api.RegistrationController;
import edu.escuelaing.techcup.tournaments.api.TournamentController;
import edu.escuelaing.techcup.tournaments.api.TournamentExportController;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.security.access.expression.ExpressionUtils;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.util.SimpleMethodInvocation;

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

    /** ADMIN inherits ORGANIZER; the use case narrows CAPTAIN down to the captain of that team. */
    @Test
    void theTeamPhotoIsManagedByCaptainsAndStaff() {
        assertThat(roleOf(TeamController.class, "replacePhoto")).isEqualTo("hasAnyRole('CAPTAIN', 'ORGANIZER')");
        assertThat(roleOf(TeamController.class, "removePhoto")).isEqualTo("hasAnyRole('CAPTAIN', 'ORGANIZER')");
    }

    @Test
    void theFormEvidenceIsUploadedByCaptains() {
        assertThat(roleOf(RegistrationController.class, "uploadFormEvidence")).isEqualTo("hasRole('CAPTAIN')");
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

    /**
     * Only ORGANIZER and ADMIN (through the role hierarchy) may set or remove the university-form
     * link; the expression is evaluated with the application's own role hierarchy, so a captain or
     * a plain player is denied (403).
     */
    @Test
    void theFormUrlIsManagedByOrganizersAndAdminsOnly() {
        TournamentController controller = new TournamentController(null, null);
        for (String write : new String[]{"updateFormUrl", "removeFormUrl"}) {
            assertThat(roleOf(TournamentController.class, write)).isEqualTo("hasRole('ORGANIZER')");
            assertThat(allows(controller, write, "ORGANIZER")).as(write + " by ORGANIZER").isTrue();
            assertThat(allows(controller, write, "ADMIN")).as(write + " by ADMIN").isTrue();
            assertThat(allows(controller, write, "CAPTAIN", "PLAYER")).as(write + " by CAPTAIN").isFalse();
            assertThat(allows(controller, write, "PLAYER")).as(write + " by PLAYER").isFalse();
            assertThat(allows(controller, write, "REFEREE")).as(write + " by REFEREE").isFalse();
        }
    }

    /** Evaluates the method's {@link PreAuthorize} for a user holding exactly {@code roles}. */
    private static boolean allows(Object controller, String methodName, String... roles) {
        Method method = methodOf(controller.getClass(), methodName);
        SecurityConfig config = new SecurityConfig();
        MethodSecurityExpressionHandler handler = SecurityConfig.methodSecurityExpressionHandler(config.roleHierarchy());
        Authentication user = new UsernamePasswordAuthenticationToken("user", null,
                AuthorityUtils.createAuthorityList(Arrays.stream(roles).map(role -> "ROLE_" + role).toArray(String[]::new)));
        EvaluationContext context = handler.createEvaluationContext(() -> user,
                new SimpleMethodInvocation(controller, method, new Object[method.getParameterCount()]));
        Expression expression = handler.getExpressionParser().parseExpression(roleOf(controller.getClass(), methodName));
        return ExpressionUtils.evaluateAsBoolean(expression, context);
    }

    private static Method methodOf(Class<?> controller, String methodName) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
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
