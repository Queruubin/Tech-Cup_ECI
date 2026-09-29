package edu.escuelaing.techcup.identity.infrastructure;

import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.identity.domain.DocumentType;
import edu.escuelaing.techcup.identity.domain.Role;
import edu.escuelaing.techcup.identity.domain.UserStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByDocumentTypeAndDocumentNumber(DocumentType documentType, String documentNumber);

    boolean existsByRolesContaining(Role role);

    List<AppUser> findByRolesContainingOrderByFullNameAsc(Role role);

    List<AppUser> findByRolesContainingAndStatusOrderByFullNameAsc(Role role, UserStatus status);

    /** Loads the row with a database write lock, serialising concurrent decisions about one user. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM AppUser u WHERE u.id = :id")
    Optional<AppUser> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            SELECT u FROM AppUser u
            WHERE :term IS NULL OR :term = ''
               OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :term, '%'))
               OR LOWER(u.email) LIKE LOWER(CONCAT('%', :term, '%'))
            ORDER BY u.fullName ASC
            """)
    List<AppUser> search(@Param("term") String term);
}
