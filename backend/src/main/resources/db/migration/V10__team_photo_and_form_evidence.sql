-- Optional photo of a team (GridFS id, kind IMAGE, owner TEAM_PHOTO). Changed by its captain, an
-- ORGANIZER or an ADMIN at any time, also while the roster is frozen by a tournament.
ALTER TABLE teams ADD COLUMN photo_file_id VARCHAR(64) NULL;

-- Evidence that the team filled in the university ("Escuela") form, uploaded with the payment
-- receipt (GridFS id, kind IMAGE_OR_PDF, owner FORM_EVIDENCE). Mandatory for new registrations and
-- for approving one; NULL only for registrations created before V10 (an UNDER_REVIEW one cannot be
-- approved until its captain uploads it, an APPROVED one stays valid).
ALTER TABLE registrations ADD COLUMN form_evidence_file_id VARCHAR(64) NULL;
