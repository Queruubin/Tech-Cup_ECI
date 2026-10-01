-- Captains can invite players: a join_requests row is either a player's REQUEST to a team or a
-- team's INVITATION to a player. Existing rows are requests.
ALTER TABLE join_requests
    ADD COLUMN direction VARCHAR(20) NOT NULL DEFAULT 'REQUEST'
        CHECK (direction IN ('REQUEST', 'INVITATION'));

-- "Pending invitations of this team to this player" and "my invitations" lookups.
CREATE INDEX ix_join_requests_player_direction ON join_requests (player_user_id, direction, status);
