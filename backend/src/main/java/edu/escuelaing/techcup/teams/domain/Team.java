package edu.escuelaing.techcup.teams.domain;

import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.shared.exception.NotFoundException;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A team and its members (aggregate root of the teams module). */
@Entity
@Table(name = "teams")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(nullable = false, length = 100)
    private String colors;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "captain_user_id", nullable = false)
    private AppUser captain;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TeamStatus status;

    @OneToMany(mappedBy = "team", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<TeamMember> members = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic lock: concurrent membership changes cannot silently overwrite each other. */
    @Version
    private Long version;

    public boolean isActive() {
        return status == TeamStatus.ACTIVE;
    }

    public boolean isCaptain(Long userId) {
        return captain.getId().equals(userId);
    }

    public int memberCount() {
        return members.size();
    }

    public boolean hasMember(Long userId) {
        return members.stream().anyMatch(m -> m.userId().equals(userId));
    }

    public List<Long> memberIds() {
        return members.stream().map(TeamMember::userId).toList();
    }

    public void addMember(AppUser user) {
        members.add(TeamMember.of(this, user));
    }

    /** @throws NotFoundException when the user is not a member */
    public void removeMember(Long userId) {
        boolean removed = members.removeIf(m -> m.userId().equals(userId));
        if (!removed) {
            throw new NotFoundException("El usuario " + userId + " no es integrante del equipo " + id + ".");
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
