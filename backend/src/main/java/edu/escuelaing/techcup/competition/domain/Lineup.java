package edu.escuelaing.techcup.competition.domain;

import edu.escuelaing.techcup.teams.domain.Team;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The lineup a captain submits for one team of one match: a formation plus exactly
 * {@value #STARTERS} starters; every other member of the team is listed as a substitute.
 */
@Entity
@Table(name = "lineups")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Lineup {

    /** Football 7: goalkeeper plus six outfield players. */
    public static final int STARTERS = 7;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "match_id", nullable = false)
    private Match match;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private Formation formation = Formation.F_2_3_1;

    @OneToMany(mappedBy = "lineup", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<LineupPlayer> players = new ArrayList<>();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Makes the collection match {@code newPlayers} as a <em>delta</em>: rows of players that are
     * still listed are updated in place (only their {@code starter} flag can change), rows of
     * players no longer listed are removed (orphan removal deletes them) and only genuinely new
     * players are added.
     *
     * <p>A naive {@code clear()} followed by {@code addAll()} does not work here: the child key
     * {@code (lineup_id, player_user_id)} is natural, so re-saving a lineup for the same roster
     * would put a removed row and a brand-new row with the same primary key into one persistence
     * context. Hibernate then either issues the INSERT before the orphan DELETE (unique-key
     * violation, surfacing as a 409) or refuses the second instance outright
     * ({@code NonUniqueObjectException}). Updating in place never creates a duplicate identity.
     */
    public void replacePlayers(List<LineupPlayer> newPlayers) {
        Map<Long, LineupPlayer> wanted = new LinkedHashMap<>();
        newPlayers.forEach(player -> wanted.put(player.playerId(), player));

        players.removeIf(existing -> !wanted.containsKey(existing.playerId()));
        for (LineupPlayer existing : players) {
            LineupPlayer incoming = wanted.remove(existing.playerId());
            existing.setStarter(incoming.isStarter());
        }
        wanted.values().forEach(player -> {
            player.setLineup(this);
            players.add(player);
        });
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
