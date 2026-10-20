package edu.escuelaing.techcup.competition.domain;

import edu.escuelaing.techcup.tournaments.domain.Tournament;
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
 * One group of the group stage of a tournament ("Grupo A", "Grupo B", ...). Groups may have
 * different sizes; the teams are assigned through {@link GroupTeam} and the group matches point
 * back at their group ({@link Match#getGroup()}).
 */
@Entity
@Table(name = "tournament_groups")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TournamentGroup {

    /** "Grupo A" .. "Grupo Z". */
    public static final int MAX_GROUPS = 26;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tournament_id", nullable = false)
    private Tournament tournament;

    @Column(nullable = false, length = 30)
    private String name;

    /** 0-based; the name derives from it and the groups are listed in this order. */
    @Column(nullable = false)
    private int position;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** "Grupo A" for position 0, "Grupo B" for 1, and so on. */
    public static String nameFor(int position) {
        if (position < 0 || position >= MAX_GROUPS) {
            throw new IllegalArgumentException("Group position out of range: " + position);
        }
        return "Grupo " + (char) ('A' + position);
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
