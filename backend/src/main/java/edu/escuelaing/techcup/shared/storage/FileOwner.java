package edu.escuelaing.techcup.shared.storage;

/**
 * What a stored file is and which domain object it belongs to. Persisted as GridFS metadata
 * ({@code kind} plus the owner field named by the category) so that downloads can be
 * authorized and orphaned files can be traced back without querying PostgreSQL.
 */
public record FileOwner(Category category, Long ownerId) {

    /** The use cases that store files, each with the name of the id they are attached to. */
    public enum Category {
        RECEIPT("teamId"),
        PHOTO("userId"),
        VENUE("tournamentId"),
        RULEBOOK("tournamentId"),
        /** An extra photo of a podium team in the history of a finished tournament (public). */
        PODIUM("tournamentId"),
        /** The optional photo of a team (any authenticated user may see it, like player photos). */
        TEAM_PHOTO("teamId"),
        /**
         * Evidence that the team filled in the university form, uploaded with a registration.
         * Private like {@link #RECEIPT}: staff and the captain of the team only.
         */
        FORM_EVIDENCE("teamId");

        private final String ownerField;

        Category(String ownerField) {
            this.ownerField = ownerField;
        }

        /** Metadata key under which the owner id is stored, e.g. {@code teamId}. */
        public String ownerField() {
            return ownerField;
        }
    }

    public FileOwner {
        if (category == null || ownerId == null) {
            throw new IllegalArgumentException("A stored file needs both a category and an owner id");
        }
    }

    /** A payment receipt uploaded by the captain of {@code teamId}. */
    public static FileOwner receipt(Long teamId) {
        return new FileOwner(Category.RECEIPT, teamId);
    }

    /** The profile photo of the player {@code userId}. */
    public static FileOwner photo(Long userId) {
        return new FileOwner(Category.PHOTO, userId);
    }

    /** The image of a venue of {@code tournamentId}. */
    public static FileOwner venue(Long tournamentId) {
        return new FileOwner(Category.VENUE, tournamentId);
    }

    /** The rulebook of {@code tournamentId}. */
    public static FileOwner rulebook(Long tournamentId) {
        return new FileOwner(Category.RULEBOOK, tournamentId);
    }

    /** A podium photo of the history of {@code tournamentId}. */
    public static FileOwner podium(Long tournamentId) {
        return new FileOwner(Category.PODIUM, tournamentId);
    }

    /** The photo of the team {@code teamId}. */
    public static FileOwner teamPhoto(Long teamId) {
        return new FileOwner(Category.TEAM_PHOTO, teamId);
    }

    /** The university-form evidence of a registration of the team {@code teamId}. */
    public static FileOwner formEvidence(Long teamId) {
        return new FileOwner(Category.FORM_EVIDENCE, teamId);
    }

    /**
     * Registration documents (payment receipt, university-form evidence): served only to
     * organizers, administrators and the captain of the owning team ({@code ownerId} = team id).
     */
    public boolean isRegistrationDocument() {
        return category == Category.RECEIPT || category == Category.FORM_EVIDENCE;
    }
}
