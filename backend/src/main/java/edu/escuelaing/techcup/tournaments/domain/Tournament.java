package edu.escuelaing.techcup.tournaments.domain;

import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A tournament edition and its venues (aggregate root of the tournaments module). */
@Entity
@Table(name = "tournaments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tournament {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "registration_deadline", nullable = false)
    private LocalDate registrationDeadline;

    @Column(name = "max_teams", nullable = false)
    private int maxTeams;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal fee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private TournamentStatus status;

    @Column(name = "rulebook_file_id", length = 64)
    private String rulebookFileId;

    /** The status a SUSPENDED tournament returns to when resumed; null while not suspended. */
    @Enumerated(EnumType.STRING)
    @Column(name = "suspended_from_status", length = 15)
    private TournamentStatus suspendedFromStatus;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private AppUser createdBy;

    @OneToMany(mappedBy = "tournament", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    @Builder.Default
    private List<Venue> venues = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic lock: two organizers editing the same tournament cannot silently overwrite each other. */
    @Version
    private Long version;

    public boolean hasRulebook() {
        return rulebookFileId != null && !rulebookFileId.isBlank();
    }

    public void addVenue(Venue venue) {
        venue.setTournament(this);
        venues.add(venue);
    }

    public void removeVenue(Venue venue) {
        venues.remove(venue);
        venue.setTournament(null);
    }

    /**
     * Applies a validated state transition (see {@link TournamentStatus}). Suspension has its own
     * entry and exit ({@link #suspend()}, {@link #resume()}), because only they keep track of the
     * status to return to; a suspended tournament refuses every other transition.
     */
    public void moveTo(TournamentStatus target) {
        if (target == TournamentStatus.SUSPENDED) {
            throw new IllegalArgumentException("Use suspend() to suspend a tournament.");
        }
        requireNotSuspended("cambiar su estado");
        status = status.transitionTo(target);
    }

    /** DRAFT, ACTIVE or IN_PROGRESS &rarr; SUSPENDED, remembering where it came from. */
    public void suspend() {
        if (status.isSuspended()) {
            throw new BusinessRuleException("El torneo «" + name + "» ya está suspendido.");
        }
        TournamentStatus from = status;
        status = status.transitionTo(TournamentStatus.SUSPENDED);
        suspendedFromStatus = from;
    }

    /**
     * SUSPENDED &rarr; the status it was suspended from.
     *
     * @return the restored status
     */
    public TournamentStatus resume() {
        if (!status.isSuspended()) {
            throw new BusinessRuleException("El torneo «" + name + "» no está suspendido; su estado actual es «"
                    + status.label() + "».");
        }
        if (suspendedFromStatus == null) {
            throw new IllegalStateException("Suspended tournament " + id + " has no status to return to.");
        }
        status = status.transitionTo(suspendedFromStatus);
        suspendedFromStatus = null;
        return status;
    }

    /**
     * Refuses any change to a suspended tournament (or to its registrations, matches, results and
     * lineups) with one message that tells the user what to do.
     *
     * @param action the Spanish infinitive phrase of what is being attempted, e.g.
     *               {@code "registrar el resultado de un partido"}
     */
    public void requireNotSuspended(String action) {
        if (status.isSuspended()) {
            throw new BusinessRuleException("El torneo «" + name + "» está suspendido: no se puede " + action
                    + " hasta que un organizador o un administrador lo reanude.");
        }
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
