-- Optional contact phone of a user, so organizers can reach team captains. Normalised by the
-- application before it is stored: "+57" followed by a 10-digit Colombian mobile, or "+" and the
-- 8-15 digits of an international number (E.164). NULL = no phone. Personal data: returned only to
-- the user, ADMIN and ORGANIZER (never in team, player, match or history responses).
ALTER TABLE users ADD COLUMN phone_number VARCHAR(20) NULL;
ALTER TABLE users ADD CONSTRAINT ck_users_phone_number
    CHECK (phone_number IS NULL OR phone_number ~ '^\+[1-9][0-9]{7,14}$');
