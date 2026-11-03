package edu.escuelaing.techcup.competition.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A team recorded as sanctioned in the history of a FINISHED tournament (by an ADMIN or
 * ORGANIZER). It is a <b>snapshot</b>: the team name and its members are copied when the entry is
 * created, so it survives the team being renamed or deleted ({@code teamId} then becomes null
 * through {@code ON DELETE SET NULL}). At most one entry per tournament and team.
 */
@Entity
@Table(name = "tournament_sanctioned_teams")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SanctionedTeam {

    public static final int MAX_NOTE_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tournament_id", nullable = false)
    private Long tournamentId;

    /** The team at the time of the sanction; null once that team was deleted. */
    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "team_name", nullable = false, length = 100)
    private String teamName;

    @Column(length = MAX_NOTE_LENGTH)
    private String note;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "sanction", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("captain DESC, fullName ASC")
    @Builder.Default
    private List<SanctionedTeamMember> members = new ArrayList<>();

    public void addMember(SanctionedTeamMember member) {
        member.setSanction(this);
        members.add(member);
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
