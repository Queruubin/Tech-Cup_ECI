package edu.escuelaing.techcup.shared.storage;

import java.util.Arrays;
import java.util.Optional;

/**
 * Recognises the real content type of an upload from its first bytes ("magic numbers"), so that
 * a client cannot smuggle an executable or an HTML page in as {@code image/png}. Only the four
 * formats the platform accepts are known; anything else is reported as unknown.
 */
final class ContentSniffer {

    /** Bytes needed to decide: WebP is the longest signature (RIFF????WEBP). */
    static final int HEADER_LENGTH = 12;

    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PDF = {0x25, 0x50, 0x44, 0x46, 0x2D};
    private static final byte[] RIFF = {0x52, 0x49, 0x46, 0x46};
    private static final byte[] WEBP = {0x57, 0x45, 0x42, 0x50};

    private ContentSniffer() {
    }

    /** The content type the header proves, or empty when it matches none of the known formats. */
    static Optional<String> detect(byte[] header) {
        if (header == null) {
            return Optional.empty();
        }
        if (startsWith(header, 0, PNG)) {
            return Optional.of("image/png");
        }
        if (startsWith(header, 0, JPEG)) {
            return Optional.of("image/jpeg");
        }
        if (startsWith(header, 0, PDF)) {
            return Optional.of("application/pdf");
        }
        if (startsWith(header, 0, RIFF) && startsWith(header, 8, WEBP)) {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] header, int offset, byte[] signature) {
        if (header.length < offset + signature.length) {
            return false;
        }
        return Arrays.equals(header, offset, offset + signature.length, signature, 0, signature.length);
    }
}
