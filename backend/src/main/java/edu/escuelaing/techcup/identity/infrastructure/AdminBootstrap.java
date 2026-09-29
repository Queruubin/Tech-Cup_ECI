package edu.escuelaing.techcup.identity.infrastructure;

import edu.escuelaing.techcup.identity.domain.AcademicProgram;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.identity.domain.DocumentType;
import edu.escuelaing.techcup.identity.domain.Role;
import edu.escuelaing.techcup.identity.domain.SchoolRelation;
import edu.escuelaing.techcup.identity.domain.UserStatus;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.config.AppProperties;
import edu.escuelaing.techcup.shared.config.DevDefaults;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the platform administrator on startup when no user holds the ADMIN role yet (spec:
 * "the administrator is registered directly at database level"). Credentials come from
 * {@code app.bootstrap.*} ({@code ADMIN_EMAIL} / {@code ADMIN_PASSWORD}), so no password hash
 * ever lives in a migration. If the e-mail already belongs to an account, that account is
 * promoted instead of failing on the unique constraint.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    static final String ENTITY_TYPE = "USER";
    static final String FULL_NAME = "System Administrator";
    static final LocalDate BIRTH_DATE = LocalDate.of(1990, 1, 1);
    static final String DOCUMENT_NUMBER = "0000000000";

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final AppProperties.Bootstrap settings;

    public AdminBootstrap(AppUserRepository users, PasswordEncoder passwordEncoder, AuditService auditService,
                          AppProperties properties) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.settings = properties.bootstrap();
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.existsByRolesContaining(Role.ADMIN)) {
            return;
        }
        if (DevDefaults.ADMIN_PASSWORD.equals(settings.adminPassword())) {
            log.warn("The administrator is being created with the DEVELOPMENT default password. "
                    + "Set ADMIN_PASSWORD before exposing this instance.");
        }
        String email = settings.adminEmail().trim().toLowerCase(Locale.ROOT);
        Optional<AppUser> existing = users.findByEmailIgnoreCase(email);
        AppUser admin = existing.map(this::promote).orElseGet(() -> create(email));
        auditService.record(null, AuditAction.ROLE_ASSIGNED, ENTITY_TYPE, admin.getId(),
                Map.of("role", Role.ADMIN.name(), "bootstrap", true, "email", admin.getEmail()));
        log.info("Administrator {} {} (id {})", admin.getEmail(), existing.isPresent() ? "promoted" : "created",
                admin.getId());
    }

    private AppUser promote(AppUser user) {
        user.getRoles().add(Role.ADMIN);
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash(passwordEncoder.encode(settings.adminPassword()));
        return user;
    }

    private AppUser create(String email) {
        return users.save(AppUser.builder()
                .fullName(FULL_NAME)
                .email(email)
                .passwordHash(passwordEncoder.encode(settings.adminPassword()))
                .schoolRelation(SchoolRelation.ADMINISTRATIVE)
                .academicProgram(AcademicProgram.OTHER)
                .status(UserStatus.ACTIVE)
                .birthDate(BIRTH_DATE)
                .documentType(DocumentType.CC)
                .documentNumber(DOCUMENT_NUMBER)
                .roles(new HashSet<>(Set.of(Role.ADMIN)))
                .build());
    }
}
