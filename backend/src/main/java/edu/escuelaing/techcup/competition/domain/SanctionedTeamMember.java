package edu.escuelaing.techcup.competition.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One member of a {@link SanctionedTeam} as it was when the sanction was recorded (name copied). */
@Entity
@Table(name = "tournament_sanctioned_team_members")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SanctionedTeamMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sanction_id", nullable = false)
    private SanctionedTeam sanction;

    /** The user at the time of the sanction; null if that account is ever deleted. */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(nullable = false)
    private boolean captain;
}
