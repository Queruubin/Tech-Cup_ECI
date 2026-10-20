-- Group stage by hand. The organizer splits the APPROVED teams of a tournament into groups
-- ("Grupo A", "Grupo B", ... of any size) and schedules the group matches one by one (or all the
-- missing pairings of one group at once); the knockout is then built from a suggestion the
-- organizer may edit. Tournaments whose group matches predate this migration keep group_id NULL
-- and behave as one implicit group (general table only).

CREATE TABLE tournament_groups (
    id            BIGSERIAL PRIMARY KEY,
    tournament_id BIGINT NOT NULL REFERENCES tournaments (id) ON DELETE CASCADE,
    name          VARCHAR(30) NOT NULL,
    -- 0-based; the name is derived from it ("Grupo A" = 0), and the groups are listed in this order.
    position      INT NOT NULL CHECK (position >= 0),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_tournament_groups_name UNIQUE (tournament_id, name),
    CONSTRAINT ux_tournament_groups_position UNIQUE (tournament_id, position),
    -- Target of the composite foreign keys below: a row pointing at a group must name the same tournament.
    CONSTRAINT ux_tournament_groups_id_tournament UNIQUE (id, tournament_id)
);

-- Target of the composite foreign key below (id alone is already unique, so this costs nothing).
ALTER TABLE registrations
    ADD CONSTRAINT ux_registrations_id_tournament_team UNIQUE (id, tournament_id, team_id);

-- Team assignments. They hang off the APPROVED registration rather than being a column of
-- registrations: the registrations table belongs to the tournaments module, which must not know
-- about the competition module (dependencies are one-way), while this table, owned by
-- competition, may reference it. Pointing at the registration also means that removing a team
-- from a tournament before it starts (the registration row is deleted) drops its assignment
-- through ON DELETE CASCADE, so a group can never hold a team that no longer takes part.
CREATE TABLE tournament_group_teams (
    id              BIGSERIAL PRIMARY KEY,
    group_id        BIGINT NOT NULL,
    tournament_id   BIGINT NOT NULL,
    team_id         BIGINT NOT NULL REFERENCES teams (id) ON DELETE CASCADE,
    registration_id BIGINT NOT NULL,
    assigned_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_group_teams_group FOREIGN KEY (group_id, tournament_id)
        REFERENCES tournament_groups (id, tournament_id) ON DELETE CASCADE,
    CONSTRAINT fk_group_teams_registration FOREIGN KEY (registration_id, tournament_id, team_id)
        REFERENCES registrations (id, tournament_id, team_id) ON DELETE CASCADE,
    -- A team belongs to at most one group per tournament.
    CONSTRAINT ux_group_teams_team UNIQUE (tournament_id, team_id)
);
CREATE INDEX ix_group_teams_group ON tournament_group_teams (group_id);

-- The group a GROUP-phase match belongs to (NULL for knockout matches and for legacy group
-- matches). Cascades like tournament -> matches, so deleting a tournament (hard delete) keeps
-- working whatever path PostgreSQL takes; the service refuses to delete a group with matches.
ALTER TABLE matches ADD COLUMN group_id BIGINT NULL;
ALTER TABLE matches
    ADD CONSTRAINT fk_matches_group FOREIGN KEY (group_id, tournament_id)
        REFERENCES tournament_groups (id, tournament_id) ON DELETE CASCADE;
ALTER TABLE matches
    ADD CONSTRAINT ck_matches_group_phase CHECK (group_id IS NULL OR phase = 'GROUP');
CREATE INDEX ix_matches_group ON matches (group_id);
