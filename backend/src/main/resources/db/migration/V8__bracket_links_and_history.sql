-- 1. Knockout feeder links. When a knockout round is drawn from the winners of the previous one,
-- every match of the previous round records which match of the new round it feeds and on which
-- side (winner of match 1 -> HOME of the next match, winner of match 2 -> AWAY, 3 -> HOME of the
-- second next match, ...). Corrections of a result follow this link. Matches drawn before this
-- migration have no link (NULL) and keep the previous behaviour: the next-phase match holding one
-- of their two teams. Undoing the next phase deletes the linked rows, which clears the link.
ALTER TABLE matches ADD COLUMN next_match_id BIGINT NULL REFERENCES matches (id) ON DELETE SET NULL;
ALTER TABLE matches ADD COLUMN next_slot VARCHAR(4) NULL CHECK (next_slot IS NULL OR next_slot IN ('HOME', 'AWAY'));
-- Two matches can never feed the same side of the same match (also the index behind ON DELETE SET NULL).
CREATE UNIQUE INDEX ux_matches_next_slot ON matches (next_match_id, next_slot) WHERE next_match_id IS NOT NULL;
-- Knockout matches that exist now were seeded under the old rule (first round listed by seed,
-- 1v8 2v7 3v6 4v5) and their next round is folded (W1vW4, W2vW3). They are marked so a bracket
-- already under way keeps that shape: the round drawn from marked matches is folded and inherits
-- the mark. Knockouts built from now on pair neighbours (W1vW2, W3vW4).
ALTER TABLE matches ADD COLUMN legacy_fold BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE matches SET legacy_fold = TRUE WHERE phase <> 'GROUP';

-- 2. Sanctioned teams of a FINISHED tournament, recorded by an ADMIN or ORGANIZER from the
-- tournament history. The team name and its members are copied at that moment (a snapshot), so
-- the entry survives the team being renamed or deleted (team_id / user_id become NULL).
CREATE TABLE tournament_sanctioned_teams (
    id            BIGSERIAL PRIMARY KEY,
    tournament_id BIGINT NOT NULL REFERENCES tournaments (id) ON DELETE CASCADE,
    team_id       BIGINT NULL REFERENCES teams (id) ON DELETE SET NULL,
    team_name     VARCHAR(100) NOT NULL,
    note          VARCHAR(500) NULL,
    created_by    BIGINT NULL REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- NULL team ids (deleted teams) never collide with each other.
    CONSTRAINT ux_sanctioned_teams_tournament_team UNIQUE (tournament_id, team_id)
);

CREATE TABLE tournament_sanctioned_team_members (
    id          BIGSERIAL PRIMARY KEY,
    sanction_id BIGINT NOT NULL REFERENCES tournament_sanctioned_teams (id) ON DELETE CASCADE,
    user_id     BIGINT NULL REFERENCES users (id) ON DELETE SET NULL,
    full_name   VARCHAR(150) NOT NULL,
    captain     BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX ix_sanctioned_team_members_sanction ON tournament_sanctioned_team_members (sanction_id);

-- 3. One optional extra photo per podium position (1 = champion, 2 = runner-up, 3 = third) of a
-- FINISHED tournament. file_id points at GridFS (kind PODIUM); the binary is deleted after commit
-- when the photo is replaced or removed, and on the tournament's hard delete.
CREATE TABLE tournament_podium_photos (
    id            BIGSERIAL PRIMARY KEY,
    tournament_id BIGINT NOT NULL REFERENCES tournaments (id) ON DELETE CASCADE,
    position      INT NOT NULL CHECK (position BETWEEN 1 AND 3),
    team_id       BIGINT NULL REFERENCES teams (id) ON DELETE SET NULL,
    file_id       VARCHAR(64) NOT NULL,
    uploaded_by   BIGINT NULL REFERENCES users (id) ON DELETE SET NULL,
    uploaded_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_podium_photos_position UNIQUE (tournament_id, position)
);
