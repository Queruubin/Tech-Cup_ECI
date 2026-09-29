package edu.escuelaing.techcup.shared.storage;

import java.util.Optional;

/**
 * Port for binary storage (hexagonal architecture). Domain modules depend on this interface;
 * the only adapter today is {@link GridFsFileStorage} (MongoDB GridFS).
 */
public interface FileStorage {

    /**
     * Validates the upload against {@code kind} (declared content type <b>and</b> the actual
     * magic bytes) and the configured size limit, stores it tagged with its {@code owner} and
     * returns its opaque id.
     *
     * @throws InvalidFileException when the file is empty, too large, of an unsupported type or
     *                              when its content does not match the declared type
     */
    String store(FileUpload upload, FileKind kind, FileOwner owner);

    /** Loads a stored file by id; empty when the id is malformed or unknown. */
    Optional<StoredFile> find(String id);

    /** Removes a stored file; a malformed or unknown id is silently ignored. */
    void delete(String id);
}
