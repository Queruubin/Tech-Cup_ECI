package edu.escuelaing.techcup.tournaments.domain;

import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.teams.domain.Team;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A team's registration for a tournament, with the payment receipt and the organizer's review.
 * The database keeps a partial unique index over (tournament, team) for the live statuses, so a
 * rejected or cancelled attempt stays as history and a new one may be submitted.
 */
@Entity
@Table(name = "registrations")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Registration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tournament_id", nullable = false)
    private Tournament tournament;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @Column(name = "receipt_file_id", nullable = false, length = 64)
    private String receiptFileId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private RegistrationStatus status;

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private AppUser reviewedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    /** Optimistic lock: an approval and a cancellation racing on the same row cannot both win. */
    @Version
    private Long version;

    /**
     * Applies a validated state transition (see {@link RegistrationStatus}) and stamps the review.
     *
     * @param reviewer the organizer taking the decision, or null when the captain cancels
     */
    public void moveTo(RegistrationStatus target, AppUser reviewer, String note) {
        status = status.transitionTo(target);
        reviewedBy = reviewer;
        reviewNote = note == null || note.isBlank() ? null : note.trim();
        reviewedAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
