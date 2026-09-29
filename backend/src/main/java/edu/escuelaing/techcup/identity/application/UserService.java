package edu.escuelaing.techcup.identity.application;

import edu.escuelaing.techcup.identity.api.dto.ChangePasswordRequest;
import edu.escuelaing.techcup.identity.api.dto.UpdateUserRequest;
import edu.escuelaing.techcup.identity.api.dto.UserResponse;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.identity.domain.Role;
import edu.escuelaing.techcup.identity.domain.SchoolRelation;
import edu.escuelaing.techcup.identity.domain.UserStatus;
import edu.escuelaing.techcup.identity.infrastructure.AppUserRepository;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.ForbiddenOperationException;
import edu.escuelaing.techcup.shared.exception.InvalidRequestException;
import edu.escuelaing.techcup.shared.exception.NotFoundException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.security.Roles;
import java.util.List;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queries and lifecycle of user accounts. Rules enforced here:
 * <ul>
 *   <li>Basic info (name, school relation, program, semester) is editable by the user or an
 *       ADMIN; e-mail is immutable. Semester is required iff STUDENT and the (immutable) e-mail
 *       must stay consistent with the new school relation.</li>
 *   <li>A user changes their own password by proving the current one; an ADMIN may reset any
 *       password.</li>
 *   <li>Inactivation is refused while the user belongs to a team with an APPROVED registration
 *       in an ACTIVE or IN_PROGRESS tournament, or while they captain an ACTIVE team.</li>
 *   <li>Personal data (e-mail, birth date, identity document) is only returned to the user
 *       themself or to an ADMIN; organizers get the e-mail in the directory listings they need
 *       to grant CAPTAIN, and nothing more.</li>
 * </ul>
 */
@Service
public class UserService {

    static final String ENTITY_TYPE = "USER";
    static final String WRONG_CURRENT_PASSWORD = "La contraseña actual no es correcta.";

    private final AppUserRepository users;
    private final UserFactsPort userFacts;
    private final EmailDomainPolicy emailDomainPolicy;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public UserService(AppUserRepository users, UserFactsPort userFacts, EmailDomainPolicy emailDomainPolicy,
                       PasswordEncoder passwordEncoder, AuditService auditService) {
        this.users = users;
        this.userFacts = userFacts;
        this.emailDomainPolicy = emailDomainPolicy;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public AppUser getUser(Long id) {
        return users.findById(id).orElseThrow(() -> NotFoundException.of("el usuario", id));
    }

    /** Loads the user with a database write lock; use inside a transaction that decides about that user. */
    @Transactional
    public AppUser getUserForUpdate(Long id) {
        return users.findByIdForUpdate(id).orElseThrow(() -> NotFoundException.of("el usuario", id));
    }

    /** The full response; for the user themself or for code paths that already checked the viewer. */
    @Transactional(readOnly = true)
    public UserResponse getResponse(Long id) {
        return toResponse(getUser(id));
    }

    /** The response as {@code viewer} is allowed to see it. */
    @Transactional(readOnly = true)
    public UserResponse getResponse(Long id, AuthenticatedUser viewer) {
        return toResponse(getUser(id), viewer);
    }

    /** Directory search for administrators and organizers; organizers do not get identity documents. */
    @Transactional(readOnly = true)
    public List<UserResponse> search(String term, AuthenticatedUser viewer) {
        return users.search(term == null ? "" : term.trim()).stream()
                .map(user -> toDirectoryResponse(user, viewer))
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean captainsActiveTeam(Long userId) {
        return userFacts.captainsActiveTeam(userId);
    }

    @Transactional
    public UserResponse updateBasicInfo(AuthenticatedUser actor, Long userId, UpdateUserRequest request) {
        if (!actor.isAdmin() && !actor.id().equals(userId)) {
            throw new ForbiddenOperationException("Solo puede modificar su propia cuenta.");
        }
        AppUser user = getUser(userId);
        validateSemester(request.schoolRelation(), request.semester());
        emailDomainPolicy.validate(user.getEmail(), request.schoolRelation());

        user.setFullName(request.fullName().trim());
        user.setSchoolRelation(request.schoolRelation());
        user.setAcademicProgram(request.academicProgram());
        user.setSemester(request.schoolRelation() == SchoolRelation.STUDENT ? request.semester() : null);

        auditService.record(actor.id(), AuditAction.USER_UPDATED, ENTITY_TYPE, user.getId(),
                Map.of("fullName", user.getFullName(),
                        "schoolRelation", user.getSchoolRelation().name(),
                        "academicProgram", user.getAcademicProgram().name()));
        return toResponse(user);
    }

    /** @throws InvalidRequestException when the current password does not match (HTTP 400) */
    @Transactional
    public void changePassword(AuthenticatedUser actor, ChangePasswordRequest request) {
        AppUser user = getUser(actor.id());
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new InvalidRequestException(WRONG_CURRENT_PASSWORD);
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        auditService.record(actor.id(), AuditAction.PASSWORD_CHANGED, ENTITY_TYPE, user.getId());
    }

    @Transactional
    public void resetPassword(AuthenticatedUser actor, Long userId, String newPassword) {
        AppUser user = getUser(userId);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        auditService.record(actor.id(), AuditAction.PASSWORD_RESET_BY_ADMIN, ENTITY_TYPE, user.getId(),
                Map.of("targetUserId", user.getId()));
    }

    @Transactional
    public UserResponse inactivate(AuthenticatedUser actor, Long userId) {
        if (actor.id().equals(userId)) {
            throw new BusinessRuleException("No puede inactivar su propia cuenta.");
        }
        AppUser user = getUser(userId);
        if (!user.isActive()) {
            throw new BusinessRuleException("El usuario ya está inactivo.");
        }
        if (userFacts.isLockedByTournament(userId)) {
            throw new BusinessRuleException("El usuario pertenece a un equipo inscrito en un torneo activo "
                    + "o en progreso, por lo que no se puede inactivar.");
        }
        if (userFacts.captainsActiveTeam(userId)) {
            throw new BusinessRuleException("El usuario es capitán de un equipo activo; "
                    + "inactive el equipo o designe otro capitán antes de inactivarlo.");
        }
        user.setStatus(UserStatus.INACTIVE);
        auditService.record(actor.id(), AuditAction.USER_INACTIVATED, ENTITY_TYPE, user.getId());
        return toResponse(user);
    }

    /** Semester is mandatory for students and meaningless for everybody else. */
    static void validateSemester(SchoolRelation relation, Integer semester) {
        if (relation == SchoolRelation.STUDENT && semester == null) {
            throw new BusinessRuleException("El semestre es obligatorio para los estudiantes.");
        }
        if (relation != SchoolRelation.STUDENT && semester != null) {
            throw new BusinessRuleException("El semestre solo aplica para los estudiantes.");
        }
    }

    /** Every field, including personal data. Use only for the user themself or an administrator. */
    public UserResponse toResponse(AppUser user) {
        return new UserResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getSchoolRelation(),
                user.getAcademicProgram(),
                user.getSemester(),
                user.getStatus(),
                user.getBirthDate(),
                user.getDocumentType(),
                user.getDocumentNumber(),
                user.getRoles().stream().sorted().map(Role::name).toList(),
                userFacts.hasSportProfile(user.getId()),
                userFacts.activeTeamIdOf(user.getId()).orElse(null));
    }

    /**
     * The profile as {@code viewer} may see it: everything for the user themself or an ADMIN,
     * otherwise the e-mail, birth date and identity document are withheld.
     */
    public UserResponse toResponse(AppUser user, AuthenticatedUser viewer) {
        UserResponse full = toResponse(user);
        return seesEverything(user, viewer) ? full : redact(full, false);
    }

    /**
     * The directory entry as {@code viewer} may see it: everything for an ADMIN; the e-mail but
     * no birth date or identity document for an ORGANIZER (who needs the e-mail to tell people
     * apart when granting CAPTAIN); nothing personal for anybody else.
     */
    public UserResponse toDirectoryResponse(AppUser user, AuthenticatedUser viewer) {
        UserResponse full = toResponse(user);
        if (seesEverything(user, viewer)) {
            return full;
        }
        return redact(full, viewer.hasRole(Roles.ORGANIZER));
    }

    private static boolean seesEverything(AppUser user, AuthenticatedUser viewer) {
        return viewer != null && (viewer.isAdmin() || viewer.id().equals(user.getId()));
    }

    private static UserResponse redact(UserResponse full, boolean keepEmail) {
        return new UserResponse(
                full.id(),
                full.fullName(),
                keepEmail ? full.email() : null,
                full.schoolRelation(),
                full.academicProgram(),
                full.semester(),
                full.status(),
                null,
                null,
                null,
                full.roles(),
                full.hasProfile(),
                full.teamId());
    }
}
