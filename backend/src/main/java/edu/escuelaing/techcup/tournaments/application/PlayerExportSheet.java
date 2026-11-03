package edu.escuelaing.techcup.tournaments.application;

import java.util.List;

/** A named sheet of the player export (e.g. "Aprobados"); it may have no rows. */
public record PlayerExportSheet(String name, List<PlayerExportRow> rows) {
}
