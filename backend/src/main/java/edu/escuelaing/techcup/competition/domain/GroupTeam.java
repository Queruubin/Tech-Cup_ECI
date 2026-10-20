package edu.escuelaing.techcup.competition.domain;

import edu.escuelaing.techcup.teams.domain.Team;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The assignment of an APPROVED team to a group. A team belongs to at most one group per
 * tournament (unique {@code (tournament_id, team_id)}). The row also names the team's approved
 * registration: the database drops the assignment when that registration is deleted (team
 * removed before the tournament starts), and composite foreign keys guarantee that the group, the
 * registration and this row all name the same tournament.
 */
@Entity
@Table(name = "tournament_group_teams")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupTeam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private TournamentGroup group;

    @Column(name = "tournament_id", nullable = false)
    private Long tournamentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @Column(name = "registration_id", nullable = false)
    private Long registrationId;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @PrePersist
    void onCreate() {
        assignedAt = Instant.now();
    }
}
