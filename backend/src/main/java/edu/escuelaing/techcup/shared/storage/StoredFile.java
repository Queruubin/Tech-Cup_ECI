package edu.escuelaing.techcup.shared.storage;

import java.io.InputStream;

/**
 * A file read back from storage, ready to be streamed to the client. {@code owner} is null for
 * files stored before owner metadata existed.
 */
public record StoredFile(String id, String filename, String contentType, long size, FileOwner owner,
                         InputStream content) {

    public boolean isPdf() {
        return "application/pdf".equalsIgnoreCase(contentType);
    }
}
