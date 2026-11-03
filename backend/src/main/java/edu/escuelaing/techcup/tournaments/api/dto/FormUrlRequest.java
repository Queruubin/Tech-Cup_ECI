package edu.escuelaing.techcup.tournaments.api.dto;

/**
 * The university-form link to set on a tournament. Validated by the use case with
 * {@code FormUrls} (required, at most 500 characters, absolute http/https URL), 400 otherwise.
 */
public record FormUrlRequest(String url) {
}
