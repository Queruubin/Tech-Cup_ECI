package edu.escuelaing.techcup.competition.domain;

import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.tournaments.domain.Tournament;
import edu.escuelaing.techcup.tournaments.domain.TournamentStatus;
import edu.escuelaing.techcup.tournaments.domain.Venue;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One fixture of a tournament with its result and its events (aggregate root of the competition module). */
@Entity
@Table(name = "matches")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Match {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tournament_id", nullable = false)
    private Tournament tournament;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private MatchPhase phase;

    /** 1-based ordinal of the matchday inside the tournament; knockout phases continue the numbering. */
    @Column(name = "round_number", nullable = false)
    private int roundNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "home_team_id", nullable = false)
    private Team homeTeam;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "away_team_id", nullable = false)
    private Team awayTeam;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_id")
    private Venue venue;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referee_user_id")
    private AppUser referee;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MatchStatus status;

    @Column(name = "home_score")
    private Integer homeScore;

    @Column(name = "away_score")
    private Integer awayScore;

    @Column(name = "home_penalties")
    private Integer homePenalties;

    @Column(name = "away_penalties")
    private Integer awayPenalties;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason", length = 15)
    private CancelReason cancelReason;

    /** The team sent through when a knockout match is cancelled (walkover); null for group matches. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "walkover_winner_team_id")
    private Team walkoverWinnerTeam;

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    @Builder.Default
    private List<MatchEvent> events = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic lock: a result and a cancellation racing on the same match cannot both win. */
    @Version
    private Long version;

    public boolean isPlayed() {
        return status == MatchStatus.PLAYED;
    }

    public boolean involves(Long teamId) {
        return homeTeam.getId().equals(teamId) || awayTeam.getId().equals(teamId);
    }

    /** The other side of the fixture. */
    public Team opponentOf(Long teamId) {
        return homeTeam.getId().equals(teamId) ? awayTeam : homeTeam;
    }

    public void addEvent(MatchEvent event) {
        event.setMatch(this);
        events.add(event);
    }

    public void clearEvents() {
        events.clear();
    }

    /** Applies a validated state transition (see {@link MatchStatus}). */
    public void moveTo(MatchStatus target) {
        status = status.transitionTo(target);
    }

    /**
     * Whether a result may be recorded or corrected right now: the tournament must be in
     * progress and the match either still SCHEDULED or PLAYED in the latest phase of the
     * tournament. Once a later phase exists its bracket was seeded from this result, so changing
     * it would silently invalidate the draw.
     *
     * @param latestPhase the most advanced phase that has matches in this tournament
     */
    public boolean isResultEditable(MatchPhase latestPhase) {
        if (tournament == null || tournament.getStatus() != TournamentStatus.IN_PROGRESS) {
            return false;
        }
        return status == MatchStatus.SCHEDULED || (status == MatchStatus.PLAYED && phase == latestPhase);
    }

    /**
     * The team that goes through in a knockout tie: the higher score, or the higher penalty
     * shoot-out when the score is level. Empty when the match has not been played or the tie is
     * still unresolved (a draw with no penalties recorded).
     */
    public Optional<Team> winner() {
        if (!isPlayed() || homeScore == null || awayScore == null) {
            return Optional.empty();
        }
        if (homeScore > awayScore) {
            return Optional.of(homeTeam);
        }
        if (awayScore > homeScore) {
            return Optional.of(awayTeam);
        }
        if (homePenalties == null || awayPenalties == null || homePenalties.equals(awayPenalties)) {
            return Optional.empty();
        }
        return Optional.of(homePenalties > awayPenalties ? homeTeam : awayTeam);
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
