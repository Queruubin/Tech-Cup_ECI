package edu.escuelaing.techcup.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.identity.api.dto.ChangePasswordRequest;
import edu.escuelaing.techcup.identity.api.dto.UpdateUserRequest;
import edu.escuelaing.techcup.identity.api.dto.UserResponse;
import edu.escuelaing.techcup.identity.domain.AcademicProgram;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.identity.domain.DocumentType;
import edu.escuelaing.techcup.identity.domain.SchoolRelation;
import edu.escuelaing.techcup.identity.domain.UserStatus;
import edu.escuelaing.techcup.identity.infrastructure.AppUserRepository;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.ForbiddenOperationException;
import edu.escuelaing.techcup.shared.exception.InvalidRequestException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, "admin@escuelaing.edu.co", Set.of("ADMIN"));
    private static final AuthenticatedUser PLAYER = new AuthenticatedUser(10L, "ana@escuelaing.edu.co", Set.of("PLAYER"));
    private static final AuthenticatedUser ORGANIZER = new AuthenticatedUser(2L, "org@escuelaing.edu.co", Set.of("ORGANIZER"));
    private static final AuthenticatedUser STRANGER = new AuthenticatedUser(11L, "bob@escuelaing.edu.co", Set.of("PLAYER"));

    @Mock
    private AppUserRepository users;
    @Mock
    private UserFactsPort userFacts;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuditService auditService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(users, userFacts, passwordEncoder, auditService);
    }

    // --- team facts ---------------------------------------------------------------------------

    @Test
    void aMemberOrTheCaptainOfAnActiveTeamBelongsToIt() {
        when(userFacts.activeTeamIdOf(10L)).thenReturn(Optional.of(5L));
        when(userFacts.activeTeamIdOf(20L)).thenReturn(Optional.empty());
        when(userFacts.captainsActiveTeam(20L)).thenReturn(true);
        when(userFacts.activeTeamIdOf(30L)).thenReturn(Optional.empty());
        when(userFacts.captainsActiveTeam(30L)).thenReturn(false);

        assertThat(userService.belongsToActiveTeam(10L)).isTrue();
        assertThat(userService.belongsToActiveTeam(20L)).isTrue();
        assertThat(userService.belongsToActiveTeam(30L)).isFalse();
    }

    // --- inactivation -------------------------------------------------------------------------

    @Test
    void inactivationIsRefusedWhileLockedByATournament() {
        when(users.findById(10L)).thenReturn(Optional.of(activeUser(10L)));
        when(userFacts.isLockedByTournament(10L)).thenReturn(true);

        assertThatThrownBy(() -> userService.inactivate(ADMIN, 10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("torneo activo o en progreso");
    }

    @Test
    void inactivationIsRefusedWhileTheUserCaptainsAnActiveTeam() {
        when(users.findById(10L)).thenReturn(Optional.of(activeUser(10L)));
        when(userFacts.isLockedByTournament(10L)).thenReturn(false);
        when(userFacts.captainsActiveTeam(10L)).thenReturn(true);

        assertThatThrownBy(() -> userService.inactivate(ADMIN, 10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("capitán de un equipo activo");
    }

    @Test
    void inactivatesAnUnlockedUser() {
        AppUser user = activeUser(10L);
        when(users.findById(10L)).thenReturn(Optional.of(user));
        when(userFacts.isLockedByTournament(10L)).thenReturn(false);
        when(userFacts.captainsActiveTeam(10L)).thenReturn(false);
        when(userFacts.activeTeamIdOf(anyLong())).thenReturn(Optional.empty());

        userService.inactivate(ADMIN, 10L);

        assertThat(user.getStatus()).isEqualTo(UserStatus.INACTIVE);
        verify(auditService).record(eq(1L), eq(AuditAction.USER_INACTIVATED), anyString(), eq(10L));
    }

    @Test
    void adminCannotInactivateThemself() {
        assertThatThrownBy(() -> userService.inactivate(ADMIN, 1L)).isInstanceOf(BusinessRuleException.class);
    }

    // --- basic info ---------------------------------------------------------------------------

    @Test
    void usersCanOnlyUpdateTheirOwnBasicInfo() {
        UpdateUserRequest request = new UpdateUserRequest("Other", SchoolRelation.STUDENT,
                AcademicProgram.AI_ENGINEERING, 4);

        assertThatThrownBy(() -> userService.updateBasicInfo(PLAYER, 11L, request))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void updateKeepsSemesterConsistentWithSchoolRelation() {
        when(users.findById(10L)).thenReturn(Optional.of(activeUser(10L)));

        assertThatThrownBy(() -> userService.updateBasicInfo(ADMIN, 10L,
                new UpdateUserRequest("Ana", SchoolRelation.GRADUATE, AcademicProgram.AI_ENGINEERING, 4)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("solo aplica para los estudiantes");
    }

    @Test
    void aStudentStillNeedsASemesterWhenEditingThemself() {
        when(users.findById(10L)).thenReturn(Optional.of(activeUser(10L)));

        assertThatThrownBy(() -> userService.updateBasicInfo(PLAYER, 10L,
                new UpdateUserRequest("Ana", SchoolRelation.STUDENT, AcademicProgram.AI_ENGINEERING, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("El semestre es obligatorio");
    }

    @Test
    void onlyAnAdminCanChangeTheSchoolRelation() {
        AppUser user = activeUser(10L);
        when(users.findById(10L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.updateBasicInfo(PLAYER, 10L,
                new UpdateUserRequest("Ana", SchoolRelation.FAMILY, AcademicProgram.OTHER, null)))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("Solo un administrador puede cambiar la relación con la Escuela.");
        assertThat(user.getSchoolRelation()).isEqualTo(SchoolRelation.STUDENT);
        verify(auditService, never()).record(any(), any(), anyString(), anyLong(), any());
    }

    @Test
    void usersEditTheirNameProgramAndSemesterWhileKeepingTheSchoolRelation() {
        AppUser user = activeUser(10L);
        when(users.findById(10L)).thenReturn(Optional.of(user));
        when(userFacts.activeTeamIdOf(10L)).thenReturn(Optional.empty());

        userService.updateBasicInfo(PLAYER, 10L,
                new UpdateUserRequest(" Ana Maria ", SchoolRelation.STUDENT, AcademicProgram.AI_ENGINEERING, 6));

        assertThat(user.getFullName()).isEqualTo("Ana Maria");
        assertThat(user.getAcademicProgram()).isEqualTo(AcademicProgram.AI_ENGINEERING);
        assertThat(user.getSemester()).isEqualTo(6);
        verify(auditService).record(eq(10L), eq(AuditAction.USER_UPDATED), anyString(), eq(10L), any());
    }

    @Test
    void anAdminChangesAnyUsersSchoolRelation() {
        AppUser user = activeUser(10L);
        when(users.findById(10L)).thenReturn(Optional.of(user));
        when(userFacts.activeTeamIdOf(10L)).thenReturn(Optional.empty());

        userService.updateBasicInfo(ADMIN, 10L,
                new UpdateUserRequest("Ana", SchoolRelation.FAMILY, AcademicProgram.OTHER, null));

        assertThat(user.getSchoolRelation()).isEqualTo(SchoolRelation.FAMILY);
        assertThat(user.getSemester()).isNull();
        verify(auditService).record(eq(1L), eq(AuditAction.USER_UPDATED), anyString(), eq(10L), any());
    }

    @Test
    void aRefereeWithoutAffiliationRenamesThemselfKeepingTheNullFields() {
        AuthenticatedUser referee = new AuthenticatedUser(30L, "arbitro@escuelaing.edu.co", Set.of("REFEREE"));
        AppUser user = activeUser(30L);
        user.setSchoolRelation(null);
        user.setAcademicProgram(null);
        user.setSemester(null);
        when(users.findById(30L)).thenReturn(Optional.of(user));
        when(userFacts.activeTeamIdOf(30L)).thenReturn(Optional.empty());

        userService.updateBasicInfo(referee, 30L, new UpdateUserRequest("Arbitro Nuevo", null, null, null));

        assertThat(user.getFullName()).isEqualTo("Arbitro Nuevo");
        assertThat(user.getSchoolRelation()).isNull();
        assertThat(user.getAcademicProgram()).isNull();
        verify(auditService).record(eq(30L), eq(AuditAction.USER_UPDATED), anyString(), eq(30L), any());
    }

    @Test
    void nullRelationAndProgramKeepTheStoredValues() {
        AppUser user = activeUser(10L);
        when(users.findById(10L)).thenReturn(Optional.of(user));
        when(userFacts.activeTeamIdOf(10L)).thenReturn(Optional.empty());

        userService.updateBasicInfo(PLAYER, 10L, new UpdateUserRequest("Ana", null, null, 7));

        assertThat(user.getSchoolRelation()).isEqualTo(SchoolRelation.STUDENT);
        assertThat(user.getAcademicProgram()).isEqualTo(AcademicProgram.SYSTEMS_ENGINEERING);
        assertThat(user.getSemester()).isEqualTo(7);
    }

    // --- eligibility data frozen during a tournament --------------------------------------------

    private static final String LOCKED_ELIGIBILITY_DATA = "El usuario está inscrito con su equipo en un torneo "
            + "activo o en curso; su relación con la Escuela y su programa no se pueden modificar hasta que el "
            + "torneo finalice.";

    @Test
    void aLockedPlayerCannotChangeTheProgramNotEvenThroughAnAdmin() {
        AppUser user = activeUser(10L);
        when(users.findById(10L)).thenReturn(Optional.of(user));
        when(userFacts.isLockedByTournament(10L)).thenReturn(true);

        for (AuthenticatedUser actor : List.of(PLAYER, ADMIN)) {
            assertThatThrownBy(() -> userService.updateBasicInfo(actor, 10L,
                    new UpdateUserRequest("Ana", SchoolRelation.STUDENT, AcademicProgram.AI_ENGINEERING, 5)))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessage(LOCKED_ELIGIBILITY_DATA);
        }
        assertThat(user.getAcademicProgram()).isEqualTo(AcademicProgram.SYSTEMS_ENGINEERING);
        verify(auditService, never()).record(any(), any(), anyString(), anyLong(), any());
    }

    @Test
    void aLockedPlayerCannotChangeTheSchoolRelationNotEvenThroughAnAdmin() {
        AppUser user = activeUser(10L);
        when(users.findById(10L)).thenReturn(Optional.of(user));
        when(userFacts.isLockedByTournament(10L)).thenReturn(true);

        assertThatThrownBy(() -> userService.updateBasicInfo(ADMIN, 10L,
                new UpdateUserRequest("Ana", SchoolRelation.FAMILY, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage(LOCKED_ELIGIBILITY_DATA);
        assertThat(user.getSchoolRelation()).isEqualTo(SchoolRelation.STUDENT);
        assertThat(user.getSemester()).isEqualTo(5);
    }

    @Test
    void aLockedPlayerStillEditsNameAndSemester() {
        AppUser user = activeUser(10L);
        when(users.findById(10L)).thenReturn(Optional.of(user));
        lenient().when(userFacts.isLockedByTournament(10L)).thenReturn(true);
        when(userFacts.activeTeamIdOf(10L)).thenReturn(Optional.of(5L));

        // Re-sending the stored relation and program is not a change.
        userService.updateBasicInfo(PLAYER, 10L,
                new UpdateUserRequest("Ana Maria", SchoolRelation.STUDENT, AcademicProgram.SYSTEMS_ENGINEERING, 6));
        userService.updateBasicInfo(PLAYER, 10L, new UpdateUserRequest("Ana María", null, null, 7));

        assertThat(user.getFullName()).isEqualTo("Ana María");
        assertThat(user.getSemester()).isEqualTo(7);
        assertThat(user.getAcademicProgram()).isEqualTo(AcademicProgram.SYSTEMS_ENGINEERING);
    }

    // --- passwords ----------------------------------------------------------------------------

    @Test
    void changingThePasswordRequiresTheCurrentOne() {
        AppUser user = activeUser(10L);
        when(users.findById(10L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

        assertThatThrownBy(() -> userService.changePassword(PLAYER, new ChangePasswordRequest("wrong", "NewPass123")))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage(UserService.WRONG_CURRENT_PASSWORD);
        assertThat(user.getPasswordHash()).isEqualTo("hash");
        verify(auditService, never()).record(any(), any(), anyString(), any());
    }

    @Test
    void changingThePasswordStoresTheNewHashAndAudits() {
        AppUser user = activeUser(10L);
        when(users.findById(10L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Secret123*", "hash")).thenReturn(true);
        when(passwordEncoder.encode("NewPass123")).thenReturn("new-hash");

        userService.changePassword(PLAYER, new ChangePasswordRequest("Secret123*", "NewPass123"));

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        verify(auditService).record(10L, AuditAction.PASSWORD_CHANGED, "USER", 10L);
    }

    @Test
    void anAdminResetsAnotherUsersPassword() {
        AppUser user = activeUser(10L);
        when(users.findById(10L)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("NewPass123")).thenReturn("new-hash");

        userService.resetPassword(ADMIN, 10L, "NewPass123");

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        verify(auditService).record(eq(1L), eq(AuditAction.PASSWORD_RESET_BY_ADMIN), eq("USER"), eq(10L), any());
    }

    // --- personal data exposure -----------------------------------------------------------------

    @Test
    void aStrangerSeesNoPersonalData() {
        UserResponse response = userService.toResponse(activeUser(10L), STRANGER);

        assertThat(response.fullName()).isEqualTo("Ana Diaz");
        assertThat(response.email()).isNull();
        assertThat(response.birthDate()).isNull();
        assertThat(response.documentType()).isNull();
        assertThat(response.documentNumber()).isNull();
    }

    @Test
    void theUserThemselfAndAdminsSeeEverything() {
        assertThat(userService.toResponse(activeUser(10L), PLAYER).documentNumber()).isEqualTo("1001");
        assertThat(userService.toResponse(activeUser(10L), ADMIN).email()).isEqualTo("ana@escuelaing.edu.co");
    }

    @Test
    void anOrganizerDoesNotSeeTheEmailOnASingleProfile() {
        assertThat(userService.toResponse(activeUser(10L), ORGANIZER).email()).isNull();
    }

    @Test
    void directoryListingsKeepTheEmailForOrganizersButHideTheDocument() {
        UserResponse response = userService.toDirectoryResponse(activeUser(10L), ORGANIZER);

        assertThat(response.email()).isEqualTo("ana@escuelaing.edu.co");
        assertThat(response.birthDate()).isNull();
        assertThat(response.documentType()).isNull();
        assertThat(response.documentNumber()).isNull();
    }

    @Test
    void directoryListingsGiveAdminsEverything() {
        assertThat(userService.toDirectoryResponse(activeUser(10L), ADMIN).documentNumber()).isEqualTo("1001");
    }

    @Test
    void searchRedactsPerViewer() {
        when(users.search("ana")).thenReturn(List.of(activeUser(10L)));

        assertThat(userService.search("ana", ORGANIZER)).singleElement()
                .satisfies(response -> {
                    assertThat(response.email()).isEqualTo("ana@escuelaing.edu.co");
                    assertThat(response.documentNumber()).isNull();
                });
    }

    private static AppUser activeUser(Long id) {
        return AppUser.builder()
                .id(id)
                .fullName("Ana Diaz")
                .email("ana@escuelaing.edu.co")
                .passwordHash("hash")
                .schoolRelation(SchoolRelation.STUDENT)
                .academicProgram(AcademicProgram.SYSTEMS_ENGINEERING)
                .semester(5)
                .status(UserStatus.ACTIVE)
                .birthDate(LocalDate.of(2002, 3, 4))
                .documentType(DocumentType.CC)
                .documentNumber("1001")
                .roles(new HashSet<>())
                .build();
    }
}
