-- Optional link to the university ("Escuela") form that captains must fill in before registering
-- (the evidence is uploaded with the registration, see V10). Set, changed or removed by an
-- ORGANIZER or an ADMIN; validated by the application (absolute http/https URL with a host, at most
-- 500 characters) because it is rendered as a link. NULL = no link configured.
ALTER TABLE tournaments ADD COLUMN form_url VARCHAR(500) NULL;
