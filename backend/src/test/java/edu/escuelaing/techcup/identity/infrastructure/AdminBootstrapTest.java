package edu.escuelaing.techcup.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.identity.domain.Role;
import edu.escuelaing.techcup.identity.domain.UserStatus;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.config.AppProperties;
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
import org.springframework.security.crypto.password.PasswordEncoder;

/** The administrator is created once, from configuration, and never duplicated. */
@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    @Mock
    private AppUserRepository users;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuditService auditService;

    private AdminBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(
                new AppProperties.Jwt("x".repeat(40), 60),
                List.of("http://localhost:5173"),
                List.of("escuelaing.edu.co"),
                "America/Bogota",
                new AppProperties.Storage(1024),
                new AppProperties.Bootstrap("Admin@Escuelaing.edu.co", "Str0ngAdminPass"));
        bootstrap = new AdminBootstrap(users, passwordEncoder, auditService, properties);
    }

    @Test
    void doesNothingWhenAnAdministratorAlreadyExists() {
        when(users.existsByRolesContaining(Role.ADMIN)).thenReturn(true);

        bootstrap.run(null);

        verify(users, never()).save(any());
        verify(auditService, never()).record(any(), any(), anyString(), any(), any());
    }

    @Test
    void createsAnActiveAdministratorFromConfiguration() {
        when(users.existsByRolesContaining(Role.ADMIN)).thenReturn(false);
        when(users.findByEmailIgnoreCase("admin@escuelaing.edu.co")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("Str0ngAdminPass")).thenReturn("hash");
        when(users.save(any())).thenAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            user.setId(1L);
            return user;
        });

        bootstrap.run(null);

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("admin@escuelaing.edu.co");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hash");
        assertThat(saved.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(saved.getValue().getRoles()).containsExactly(Role.ADMIN);
        verify(auditService).record(eq(null), eq(AuditAction.ROLE_ASSIGNED), anyString(), eq(1L), any());
    }

    @Test
    void promotesAnExistingAccountWithTheConfiguredEmailInsteadOfDuplicatingIt() {
        AppUser existing = AppUser.builder().id(4L).email("admin@escuelaing.edu.co").status(UserStatus.INACTIVE)
                .roles(new HashSet<>(Set.of(Role.PLAYER))).build();
        when(users.existsByRolesContaining(Role.ADMIN)).thenReturn(false);
        when(users.findByEmailIgnoreCase("admin@escuelaing.edu.co")).thenReturn(Optional.of(existing));
        when(passwordEncoder.encode("Str0ngAdminPass")).thenReturn("hash");

        bootstrap.run(null);

        verify(users, never()).save(any());
        assertThat(existing.getRoles()).contains(Role.ADMIN, Role.PLAYER);
        assertThat(existing.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(existing.getPasswordHash()).isEqualTo("hash");
    }
}
