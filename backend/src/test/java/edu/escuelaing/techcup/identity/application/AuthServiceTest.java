package edu.escuelaing.techcup.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.identity.api.dto.LoginRequest;
import edu.escuelaing.techcup.identity.api.dto.RegisterRequest;
import edu.escuelaing.techcup.identity.domain.AcademicProgram;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.identity.domain.DocumentType;
import edu.escuelaing.techcup.identity.domain.Role;
import edu.escuelaing.techcup.identity.domain.SchoolRelation;
import edu.escuelaing.techcup.identity.domain.UserStatus;
import edu.escuelaing.techcup.identity.infrastructure.AppUserRepository;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.ConflictException;
import edu.escuelaing.techcup.shared.exception.LoginRateLimitException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.security.JwtService;
import edu.escuelaing.techcup.shared.security.TokenDenylist;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T10:00:00Z");

    @Mock
    private AppUserRepository users;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private UserService userService;
    @Mock
    private AuditService auditService;

    private TokenDenylist denylist;
    private LoginAttemptService loginAttempts;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        PlayerAgePolicy policy = new PlayerAgePolicy(5, 100, clock);
        denylist = new TokenDenylist(clock);
        loginAttempts = new LoginAttemptService(clock);
        authService = new AuthService(users, passwordEncoder, policy, jwtService, denylist, loginAttempts,
                userService, auditService);
    }

    // --- register -----------------------------------------------------------------------------

    @Test
    void registersAnActiveStudentWithTheChosenRole() {
        when(users.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(users.existsByDocumentTypeAndDocumentNumber(any(), anyString())).thenReturn(false);
        when(passwordEncoder.encode("Secret123*")).thenReturn("hash");
        when(users.save(any())).thenAnswer(inv -> {
            AppUser u = inv.getArgument(0);
            u.setId(7L);
            return u;
        });

        authService.register(request(SchoolRelation.STUDENT, 5, "Ana@Escuelaing.edu.co", Role.PLAYER));

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("ana@escuelaing.edu.co");
        assertThat(saved.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(saved.getValue().getRoles()).containsExactly(Role.PLAYER);
        assertThat(saved.getValue().getSemester()).isEqualTo(5);
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hash");
        verify(auditService).record(eq(null), eq(AuditAction.USER_REGISTERED), anyString(), eq(7L), any());
    }

    @Test
    void semesterIsRequiredForStudents() {
        assertThatThrownBy(() -> authService.register(
                request(SchoolRelation.STUDENT, null, "ana@escuelaing.edu.co", Role.PLAYER)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("El semestre es obligatorio");
        verify(users, never()).save(any());
    }

    @Test
    void semesterIsRejectedForNonStudents() {
        assertThatThrownBy(() -> authService.register(
                request(SchoolRelation.PROFESSOR, 3, "ana@escuelaing.edu.co", Role.PLAYER)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("solo aplica para los estudiantes");
    }

    @Test
    void initialRoleMustBePlayerOrGuest() {
        assertThatThrownBy(() -> authService.register(
                request(SchoolRelation.STUDENT, 5, "ana@escuelaing.edu.co", Role.ORGANIZER)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("jugador o invitado");
    }

    @Test
    void anyEmailDomainMayRegisterWhateverTheSchoolRelation() {
        stubSuccessfulSave();

        authService.register(request(SchoolRelation.FAMILY, null, "uncle@escuelaing.edu.co", Role.PLAYER));
        authService.register(request(SchoolRelation.STUDENT, 5, "ana@gmail.com", Role.PLAYER));

        verify(users, times(2)).save(any());
    }

    @Test
    void aPlayerOlderThanTheRangeIsRefused() {
        assertThatThrownBy(() -> authService.register(request(SchoolRelation.STUDENT, 5, "ana@escuelaing.edu.co",
                Role.PLAYER, LocalDate.of(1920, 3, 4))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Para ser jugador la edad debe estar entre 5 y 100 años.");
        verify(users, never()).save(any());
    }

    @Test
    void aPlayerYoungerThanTheRangeIsRefused() {
        assertThatThrownBy(() -> authService.register(request(SchoolRelation.FAMILY, null, "kid@gmail.com",
                Role.PLAYER, LocalDate.of(2022, 1, 1))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("entre 5 y 100 años");
    }

    @Test
    void guestsHaveNoAgeLimit() {
        stubSuccessfulSave();

        authService.register(request(SchoolRelation.PROFESSOR, null, "profe@escuelaing.edu.co",
                Role.GUEST, LocalDate.of(1970, 5, 1)));

        verify(users).save(any());
    }

    @Test
    void rejectsDuplicateEmail() {
        when(users.existsByEmailIgnoreCase("ana@escuelaing.edu.co")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                request(SchoolRelation.STUDENT, 5, "ana@escuelaing.edu.co", Role.PLAYER)))
                .isInstanceOf(ConflictException.class)
                .hasMessage(AuthService.DUPLICATE_ACCOUNT_MESSAGE);
    }

    @Test
    void aDuplicateDocumentGetsTheSameMessageAsADuplicateEmail() {
        when(users.existsByEmailIgnoreCase("ana@escuelaing.edu.co")).thenReturn(false);
        when(users.existsByDocumentTypeAndDocumentNumber(DocumentType.CC, "1001")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                request(SchoolRelation.STUDENT, 5, "ana@escuelaing.edu.co", Role.PLAYER)))
                .isInstanceOf(ConflictException.class)
                .hasMessage(AuthService.DUPLICATE_ACCOUNT_MESSAGE);
    }

    // --- login --------------------------------------------------------------------------------

    @Test
    void aFailedLoginIsAuditedInItsOwnTransaction() {
        when(users.findByEmailIgnoreCase("ana@escuelaing.edu.co")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("Ana@escuelaing.edu.co", "wrong")))
                .isInstanceOf(BadCredentialsException.class);

        verify(auditService).recordDetached(isNull(), eq(AuditAction.LOGIN_FAILED), anyString(), isNull(), any());
        verify(jwtService, never()).issue(any(), any(), any());
    }

    /**
     * A login must not hold a connection for the whole attempt while the detached failure audit
     * asks for a second one: under a burst of failures that exhausted the pool.
     */
    @Test
    void loginDoesNotRunInsideASurroundingTransaction() throws NoSuchMethodException {
        var login = AuthService.class.getMethod("login", LoginRequest.class);

        assertThat(login.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
        assertThat(AuthService.class.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class))
                .isFalse();
    }

    @Test
    void theSixthFailedAttemptIsThrottledWithoutTouchingTheAccount() {
        when(users.findByEmailIgnoreCase("ana@escuelaing.edu.co")).thenReturn(Optional.empty());
        LoginRequest request = new LoginRequest("ana@escuelaing.edu.co", "wrong");
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> authService.login(request)).isInstanceOf(BadCredentialsException.class);
        }

        assertThatThrownBy(() -> authService.login(request)).isInstanceOf(LoginRateLimitException.class);

        verify(users, times(5)).findByEmailIgnoreCase("ana@escuelaing.edu.co");
    }

    @Test
    void aSuccessfulLoginIssuesATokenAndClearsTheCounter() {
        AppUser user = activeUser();
        when(users.findByEmailIgnoreCase("ana@escuelaing.edu.co")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);
        when(passwordEncoder.matches("Secret123*", "hash")).thenReturn(true);
        when(jwtService.issue(eq(10L), eq("ana@escuelaing.edu.co"), any()))
                .thenReturn(new JwtService.IssuedToken("jwt", NOW.plusSeconds(3600)));
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("ana@escuelaing.edu.co", "wrong")))
                    .isInstanceOf(BadCredentialsException.class);
        }

        var response = authService.login(new LoginRequest("ana@escuelaing.edu.co", "Secret123*"));

        assertThat(response.token()).isEqualTo("jwt");
        assertThat(loginAttempts.isBlocked(LoginAttemptService.emailKey("ana@escuelaing.edu.co"))).isFalse();
        verify(auditService).record(10L, AuditAction.LOGIN, "USER", 10L);
    }

    // --- logout -------------------------------------------------------------------------------

    @Test
    void logoutRevokesThePresentedToken() {
        AuthenticatedUser actor = new AuthenticatedUser(10L, "ana@escuelaing.edu.co", Set.of("PLAYER"));
        when(jwtService.parse("jwt")).thenReturn(Optional.of(new JwtService.TokenClaims(10L,
                "ana@escuelaing.edu.co", List.of("PLAYER"), "token-id", NOW.plusSeconds(3600))));

        authService.logout(actor, "jwt");

        assertThat(denylist.isDenied("token-id")).isTrue();
        verify(auditService).record(10L, AuditAction.LOGOUT, "USER", 10L);
    }

    @Test
    void logoutWithoutAReadableTokenStillAudits() {
        AuthenticatedUser actor = new AuthenticatedUser(10L, "ana@escuelaing.edu.co", Set.of("PLAYER"));

        authService.logout(actor, null);

        verify(jwtService, never()).parse(any());
        verify(auditService).record(10L, AuditAction.LOGOUT, "USER", 10L);
    }

    // --- helpers ------------------------------------------------------------------------------

    private static AppUser activeUser() {
        return AppUser.builder().id(10L).email("ana@escuelaing.edu.co").passwordHash("hash")
                .status(UserStatus.ACTIVE).roles(new HashSet<>(Set.of(Role.PLAYER))).build();
    }

    private void stubSuccessfulSave() {
        when(users.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(users.existsByDocumentTypeAndDocumentNumber(any(), anyString())).thenReturn(false);
        when(users.save(any())).thenAnswer(inv -> {
            AppUser u = inv.getArgument(0);
            u.setId(7L);
            return u;
        });
    }

    /** A ten-year-old on the fixed clock date, inside the default 5 to 13 player range. */
    private static RegisterRequest request(SchoolRelation relation, Integer semester, String email, Role role) {
        return request(relation, semester, email, role, LocalDate.of(2016, 3, 4));
    }

    private static RegisterRequest request(SchoolRelation relation, Integer semester, String email, Role role,
                                           LocalDate birthDate) {
        return new RegisterRequest("Ana Diaz", email, "Secret123*", relation, AcademicProgram.SYSTEMS_ENGINEERING,
                semester, birthDate, DocumentType.CC, "1001", role);
    }
}
