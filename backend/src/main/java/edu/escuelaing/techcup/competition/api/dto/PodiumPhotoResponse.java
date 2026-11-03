package edu.escuelaing.techcup.competition.api.dto;

/** The podium photo of one position (1, 2 or 3) of a finished tournament. */
public record PodiumPhotoResponse(int position, Long teamId, String fileId) {
}
