package edu.escuelaing.techcup.competition.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The optional extra photo of one podium position (1 champion, 2 runner-up, 3 third) in the
 * history of a FINISHED tournament, separate from the players' profile photos. One per position
 * (unique {@code (tournament_id, position)}); {@code fileId} points at GridFS (owner PODIUM), is
 * public to read through the history and deleted after commit when replaced or removed, and with
 * the tournament's hard delete.
 */
@Entity
@Table(name = "tournament_podium_photos")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PodiumPhoto {

    public static final int MIN_POSITION = 1;
    public static final int MAX_POSITION = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tournament_id", nullable = false)
    private Long tournamentId;

    @Column(nullable = false)
    private int position;

    /** The team holding the position when the photo was uploaded. */
    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "file_id", nullable = false, length = 64)
    private String fileId;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        uploadedAt = Instant.now();
    }
}
