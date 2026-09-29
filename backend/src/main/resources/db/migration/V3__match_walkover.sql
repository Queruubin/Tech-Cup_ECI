-- Knockout walkovers and optimistic locking.

-- A cancelled knockout match still has to send a team through: the organizer names the winner.
ALTER TABLE matches
    ADD COLUMN walkover_winner_team_id BIGINT NULL REFERENCES teams (id);

-- @Version columns for the aggregates that concurrent requests may touch at the same time.
ALTER TABLE tournaments   ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE registrations ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE join_requests ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE matches       ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE teams         ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
