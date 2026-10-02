package edu.escuelaing.techcup.identity.application;

import edu.escuelaing.techcup.identity.api.dto.LoginRequest;
import edu.escuelaing.techcup.identity.api.dto.LoginResponse;
import edu.escuelaing.techcup.identity.api.dto.RegisterRequest;
import edu.escuelaing.techcup.identity.api.dto.UserResponse;
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
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.security.JwtService;
import edu.escuelaing.techcup.shared.security.TokenDenylist;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and session use cases (spec 7.1). Rules enforced here:
 * <ul>
 *   <li>Self-registration may only pick PLAYER or GUEST as initial role.</li>
 *   <li>Semester is required iff the user is a STUDENT.</li>
 *   <li>Any e-mail may register (no domain rule).</li>
 *   <li>Registering as PLAYER requires an age inside the configured range ({@link PlayerAgePolicy});
 *       GUEST has no age limit.</li>
 *   <li>E-mail and identity document are unique (reported with one generic message so the
 *       endpoint cannot be used to find out which e-mails are registered); new accounts start
 *       ACTIVE.</li>
 *   <li>Login requires an ACTIVE account and issues a stateless JWT; repeated failures for one
 *       e-mail are throttled by {@link LoginAttemptService}.</li>
 *   <li>Logout revokes the presented token through the {@link TokenDenylist}.</li>
 * </ul>
 * Every use case is audited (USER_REGISTERED, LOGIN, LOGIN_FAILED, LOGOUT).
 */
@Service
public class AuthService {

    static final String DUPLICATE_ACCOUNT_MESSAGE =
            "Ya existe una cuenta registrada con ese correo o con ese documento de identidad.";

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PlayerAgePolicy playerAgePolicy;
    private final JwtService jwtService;
    private final TokenDenylist tokenDenylist;
    private final LoginAttemptService loginAttempts;
    private final UserService userService;
    private final AuditService auditService;

    public AuthService(AppUserRepository users, PasswordEncoder passwordEncoder, PlayerAgePolicy playerAgePolicy,
                       JwtService jwtService, TokenDenylist tokenDenylist, LoginAttemptService loginAttempts,
                       UserService userService, AuditService auditService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.playerAgePolicy = playerAgePolicy;
        this.jwtService = jwtService;
        this.tokenDenylist = tokenDenylist;
        this.loginAttempts = loginAttempts;
        this.userService = userService;
        this.auditService = auditService;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        // The registration form no longer offers a role: accounts are players unless a client
        // explicitly asks for GUEST. The school relation is optional and set later by an admin.
        Role initialRole = request.initialRole() != null ? request.initialRole() : Role.PLAYER;
        if (!initialRole.isSelfAssignable()) {
            throw new BusinessRuleException("El rol inicial solo puede ser jugador o invitado.");
        }
        UserService.validateSemester(request.schoolRelation(), request.semester());
        if (initialRole == Role.PLAYER) {
            playerAgePolicy.validate(request.birthDate());
        }
        String email = normalizeEmail(request.email());
        ensureEmailAndDocumentAvailable(email, request.documentType(), request.documentNumber());

        AppUser user = AppUser.builder()
                .fullName(request.fullName().trim())
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .schoolRelation(request.schoolRelation())
                .academicProgram(request.academicProgram())
                .semester(request.schoolRelation() == SchoolRelation.STUDENT ? request.semester() : null)
                .status(UserStatus.ACTIVE)
                .birthDate(request.birthDate())
                .documentType(request.documentType())
                .documentNumber(request.documentNumber().trim())
                .roles(new HashSet<>(Set.of(initialRole)))
                .build();
        user = users.save(user);

        auditService.record(null, AuditAction.USER_REGISTERED, UserService.ENTITY_TYPE, user.getId(),
                Map.of("email", user.getEmail(), "initialRole", initialRole.name()));
        return userService.toResponse(user);
    }

    /**
     * Deliberately <b>not</b> {@code @Transactional}: every repository call and audit write runs
     * in its own short transaction. A surrounding transaction would hold one JDBC connection for
     * the whole (slow, BCrypt-bound) attempt while the failure audit needs a second one, so a burst
     * of failed logins could exhaust the connection pool. The only write of a successful login is
     * its audit row, so nothing is lost by not sharing a transaction.
     */
    public LoginResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        loginAttempts.assertAllowed(email);

        Optional<AppUser> match = users.findByEmailIgnoreCase(email)
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()));
        if (match.isEmpty()) {
            loginAttempts.recordFailure(email);
            // Detached: the audit row must survive the exception below, even if a caller ever wraps
            // this method in a transaction again.
            auditService.recordDetached(null, AuditAction.LOGIN_FAILED, UserService.ENTITY_TYPE, null,
                    Map.of("email", email));
            throw new BadCredentialsException("El correo o la contraseña no son correctos.");
        }
        AppUser user = match.get();
        if (!user.isActive()) {
            throw new DisabledException("La cuenta está inactiva. Comuníquese con un administrador.");
        }
        loginAttempts.reset(email);
        JwtService.IssuedToken token = jwtService.issue(user.getId(), user.getEmail(),
                user.getRoles().stream().map(Role::name).toList());
        auditService.record(user.getId(), AuditAction.LOGIN, UserService.ENTITY_TYPE, user.getId());
        return new LoginResponse(token.token(), token.expiresAt(), userService.toResponse(user));
    }

    /**
     * Revokes the presented token until its natural expiry and records the event.
     *
     * @param token the raw bearer token of the request, or null when it could not be read
     */
    @Transactional
    public void logout(AuthenticatedUser actor, String token) {
        if (token != null) {
            jwtService.parse(token).ifPresent(claims -> tokenDenylist.deny(claims.tokenId(), claims.expiresAt()));
        }
        auditService.record(actor.id(), AuditAction.LOGOUT, UserService.ENTITY_TYPE, actor.id());
    }

    @Transactional(readOnly = true)
    public UserResponse me(AuthenticatedUser actor) {
        return userService.getResponse(actor.id());
    }

    /**
     * One generic message for both clashes: a distinct "e-mail already registered" answer would
     * let anyone enumerate the accounts of the platform.
     */
    private void ensureEmailAndDocumentAvailable(String email, DocumentType documentType, String documentNumber) {
        if (users.existsByEmailIgnoreCase(email)
                || users.existsByDocumentTypeAndDocumentNumber(documentType, documentNumber.trim())) {
            throw new ConflictException(DUPLICATE_ACCOUNT_MESSAGE);
        }
    }

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
