-- A double submit (two clicks, two tabs) used to insert two PENDING rows, because the services
-- check "already pending?" and then insert. The database now guarantees it:
--   * a player has at most one PENDING request (direction REQUEST), whatever the team;
--   * a team has at most one PENDING invitation (direction INVITATION) for the same player.
-- The services translate a violation of these indexes into their usual 409 message.

-- Close duplicates that may already exist, keeping the most recent PENDING row of each group,
-- so the unique indexes can be created on any existing database.
UPDATE join_requests jr
SET status = 'CANCELLED', resolved_at = now()
WHERE jr.status = 'PENDING'
  AND jr.direction = 'REQUEST'
  AND EXISTS (SELECT 1 FROM join_requests newer
              WHERE newer.status = 'PENDING'
                AND newer.direction = 'REQUEST'
                AND newer.player_user_id = jr.player_user_id
                AND newer.id > jr.id);

UPDATE join_requests jr
SET status = 'CANCELLED', resolved_at = now()
WHERE jr.status = 'PENDING'
  AND jr.direction = 'INVITATION'
  AND EXISTS (SELECT 1 FROM join_requests newer
              WHERE newer.status = 'PENDING'
                AND newer.direction = 'INVITATION'
                AND newer.team_id = jr.team_id
                AND newer.player_user_id = jr.player_user_id
                AND newer.id > jr.id);

CREATE UNIQUE INDEX ux_join_requests_pending_request
    ON join_requests (player_user_id)
    WHERE status = 'PENDING' AND direction = 'REQUEST';

CREATE UNIQUE INDEX ux_join_requests_pending_invitation
    ON join_requests (team_id, player_user_id)
    WHERE status = 'PENDING' AND direction = 'INVITATION';
