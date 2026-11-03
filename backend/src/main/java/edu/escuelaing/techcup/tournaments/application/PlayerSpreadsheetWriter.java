package edu.escuelaing.techcup.tournaments.application;

import java.util.List;

/**
 * Port that renders the player export as a spreadsheet file. The use case builds the rows; the
 * adapter ({@code tournaments.infrastructure.XlsxPlayerSpreadsheetWriter}, Apache POI) owns the
 * file format, so the application layer never depends on the library.
 */
public interface PlayerSpreadsheetWriter {

    /** Media type of the files this writer produces. */
    String contentType();

    /** File extension, without the dot (e.g. {@code xlsx}). */
    String extension();

    /** Writes every sheet, in order, each with the header row first. */
    byte[] write(List<PlayerExportSheet> sheets);
}
