package edu.escuelaing.techcup.players.domain;

import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
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
 * A player's request to join a team, or a team's invitation to a player ({@link #direction}).
 * Only the team id is stored (no JPA link to the teams module); state transitions are guarded
 * here (State pattern: only PENDING can move on).
 */
@Entity
@Table(name = "join_requests")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JoinRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_user_id", nullable = false)
    private AppUser player;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private JoinRequestStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private JoinRequestDirection direction = JoinRequestDirection.REQUEST;

    @Column(length = 500)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** Optimistic lock: an accept and a cancel racing on the same request cannot both win. */
    @Version
    private Long version;

    public boolean isInvitation() {
        return direction == JoinRequestDirection.INVITATION;
    }

    public boolean isPending() {
        return status == JoinRequestStatus.PENDING;
    }

    public void accept() {
        transition(JoinRequestStatus.ACCEPTED);
    }

    public void reject() {
        transition(JoinRequestStatus.REJECTED);
    }

    public void cancel() {
        transition(JoinRequestStatus.CANCELLED);
    }

    private void transition(JoinRequestStatus next) {
        if (!isPending()) {
            throw new BusinessRuleException("La " + direction.label() + " ya está " + status.label() + ".");
        }
        status = next;
        resolvedAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = JoinRequestStatus.PENDING;
        }
        if (direction == null) {
            direction = JoinRequestDirection.REQUEST;
        }
    }
}
