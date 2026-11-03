package edu.escuelaing.techcup.competition.application;

import edu.escuelaing.techcup.competition.api.dto.PodiumPhotoResponse;
import edu.escuelaing.techcup.competition.api.dto.SanctionedTeamResponse;
import edu.escuelaing.techcup.competition.api.dto.TournamentHistoryResponse;
import edu.escuelaing.techcup.competition.api.dto.TournamentHistorySummary;
import edu.escuelaing.techcup.competition.domain.Match;
import edu.escuelaing.techcup.competition.domain.PodiumPhoto;
import edu.escuelaing.techcup.competition.domain.SanctionedTeam;
import edu.escuelaing.techcup.competition.domain.SanctionedTeamMember;
import edu.escuelaing.techcup.competition.domain.TournamentRecords;
import edu.escuelaing.techcup.competition.infrastructure.MatchEventRepository;
import edu.escuelaing.techcup.competition.infrastructure.MatchRepository;
import edu.escuelaing.techcup.competition.infrastructure.PodiumPhotoRepository;
import edu.escuelaing.techcup.competition.infrastructure.SanctionedTeamRepository;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.DataIntegrity;
import edu.escuelaing.techcup.shared.exception.InvalidRequestException;
import edu.escuelaing.techcup.shared.exception.NotFoundException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.storage.FileDeletionScheduler;
import edu.escuelaing.techcup.shared.storage.FileKind;
import edu.escuelaing.techcup.shared.storage.FileOwner;
import edu.escuelaing.techcup.shared.storage.FileStorage;
import edu.escuelaing.techcup.shared.storage.FileUpload;
import edu.escuelaing.techcup.shared.storage.StoredFile;
import edu.escuelaing.techcup.teams.application.TeamRosterEntry;
import edu.escuelaing.techcup.teams.application.TeamService;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.tournaments.application.TournamentService;
import edu.escuelaing.techcup.tournaments.domain.Tournament;
import edu.escuelaing.techcup.tournaments.domain.TournamentStatus;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * The history of FINISHED tournaments (public, anonymous visitors included): podium, best defence,
 * top scorers ({@link TournamentRecords}, recomputed from the matches on every read), the
 * sanctioned teams and the optional podium photos that ADMIN/ORGANIZER add from the same view.
 *
 * <p>It lives in the competition module because every number comes from the matches; the two
 * pieces of persisted data it adds (sanctioned teams, podium photos) are only meaningful next to
 * those numbers, so they live here too. The tournaments module only learns about the photos'
 * binaries through {@code TournamentFilesPort} (hard delete).
 *
 * <p>Rules: writes only for a FINISHED tournament (409 otherwise), under the tournament row lock.
 * A sanctioned team must have taken part (approved registration or a match), at most once per
 * tournament, and is stored as a snapshot (name and members copied). A podium photo needs a team
 * at that position; one photo per position, the replaced binary deleted after commit. Audited as
 * TOURNAMENT_TEAM_SANCTIONED / TOURNAMENT_SANCTION_REMOVED / PODIUM_PHOTO_UPDATED / PODIUM_PHOTO_REMOVED.
 */
@Service
public class TournamentHistoryService {

    static final String TOURNAMENT_ENTITY_TYPE = "TOURNAMENT";
    static final String SANCTION_DUPLICATE = "El equipo '%s' ya figura como sancionado en este torneo.";
    private static final String UNIQUE_SANCTION = "ux_sanctioned_teams_tournament_team";

    private final MatchRepository matches;
    private final MatchEventRepository events;
    private final SanctionedTeamRepository sanctions;
    private final PodiumPhotoRepository podiumPhotos;
    private final TournamentService tournamentService;
    private final TeamService teamService;
    private final StandingsService standingsService;
    private final FileStorage fileStorage;
    private final FileDeletionScheduler fileDeletion;
    private final AuditService auditService;

    public TournamentHistoryService(MatchRepository matches, MatchEventRepository events,
                                    SanctionedTeamRepository sanctions, PodiumPhotoRepository podiumPhotos,
                                    TournamentService tournamentService, TeamService teamService,
                                    StandingsService standingsService, FileStorage fileStorage,
                                    FileDeletionScheduler fileDeletion, AuditService auditService) {
        this.matches = matches;
        this.events = events;
        this.sanctions = sanctions;
        this.podiumPhotos = podiumPhotos;
        this.tournamentService = tournamentService;
        this.teamService = teamService;
        this.standingsService = standingsService;
        this.fileStorage = fileStorage;
        this.fileDeletion = fileDeletion;
        this.auditService = auditService;
    }

    // --- reads (public) ------------------------------------------------------------------------

    /** Every FINISHED tournament, the one that ended last first, with its champion. */
    @Transactional(readOnly = true)
    public List<TournamentHistorySummary> finished() {
        return tournamentService.finishedTournaments().stream()
                .map(tournament -> {
                    TournamentRecords.Podium podium = podiumOf(tournament.getId(), allMatches(tournament.getId()));
                    TournamentHistorySummary.Champion champion = podium.place(1)
                            .map(place -> new TournamentHistorySummary.Champion(place.teamId(), place.teamName()))
                            .orElse(null);
                    return new TournamentHistorySummary(tournament.getId(), tournament.getName(),
                            tournament.getStartDate(), tournament.getEndDate(), sourceName(podium), champion);
                })
                .toList();
    }

    /** @param viewer the caller, or null for an anonymous visitor */
    @Transactional(readOnly = true)
    public TournamentHistoryResponse history(Long tournamentId, AuthenticatedUser viewer) {
        Tournament tournament = tournamentService.requireVisible(tournamentId, viewer);
        requireFinished(tournament);
        List<Match> all = allMatches(tournamentId);
        TournamentRecords.Podium podium = podiumOf(tournamentId, all);
        Map<Integer, PodiumPhoto> photos = podiumPhotos.findByTournamentIdOrderByPositionAsc(tournamentId).stream()
                .collect(Collectors.toMap(PodiumPhoto::getPosition, Function.identity()));

        List<TournamentHistoryResponse.PodiumEntry> entries = podium.places().stream()
                .map(place -> new TournamentHistoryResponse.PodiumEntry(place.position(), place.teamId(),
                        place.teamName(), place.played(), place.points(), place.goalDifference(), place.goalsFor(),
                        place.goalsAgainst(), photos.containsKey(place.position())
                                ? photos.get(place.position()).getFileId() : null))
                .toList();
        List<TournamentHistoryResponse.TeamNumbers> candidates = podium.thirdPlaceCandidates().stream()
                .map(tally -> new TournamentHistoryResponse.TeamNumbers(tally.teamId(), tally.teamName(), tally.played(),
                        tally.points(), tally.goalDifference(), tally.goalsFor(), tally.goalsAgainst()))
                .toList();
        TournamentHistoryResponse.BestDefence defence = TournamentRecords.bestDefence(all)
                .map(best -> new TournamentHistoryResponse.BestDefence(best.teamId(), best.teamName(),
                        best.goalsConceded(), best.matchesPlayed()))
                .orElse(null);
        List<TournamentHistoryResponse.TopScorer> scorers = TournamentRecords.topScorers(scorerRows(tournamentId)).stream()
                .map(scorer -> new TournamentHistoryResponse.TopScorer(scorer.playerId(), scorer.playerName(),
                        scorer.teamId(), scorer.teamName(), scorer.goals()))
                .toList();
        List<SanctionedTeamResponse> sanctioned = sanctions.findByTournamentIdOrderByCreatedAtAscIdAsc(tournamentId)
                .stream().map(TournamentHistoryService::toResponse).toList();
        List<TournamentHistoryResponse.TeamRef> participants = participants(tournamentId, all).values().stream()
                .map(team -> new TournamentHistoryResponse.TeamRef(team.getId(), team.getName()))
                .sorted(Comparator.comparing(TournamentHistoryResponse.TeamRef::name, String.CASE_INSENSITIVE_ORDER))
                .toList();

        return new TournamentHistoryResponse(tournament.getId(), tournament.getName(), tournament.getStartDate(),
                tournament.getEndDate(), sourceName(podium), entries, candidates, defence, scorers, sanctioned,
                participants);
    }

    /**
     * The binary of a podium photo, for anybody who may see the tournament (anonymous included):
     * 404 when there is no photo at that position.
     */
    @Transactional(readOnly = true)
    public StoredFile podiumPhoto(Long tournamentId, int position, AuthenticatedUser viewer) {
        tournamentService.requireVisible(tournamentId, viewer);
        PodiumPhoto photo = podiumPhotos.findByTournamentIdAndPosition(tournamentId, position)
                .orElseThrow(() -> new NotFoundException("El torneo no tiene foto para el " + position + "° puesto."));
        return fileStorage.find(photo.getFileId())
                .orElseThrow(() -> new NotFoundException("El torneo no tiene foto para el " + position + "° puesto."));
    }

    // --- sanctioned teams (ADMIN / ORGANIZER) ------------------------------------------------

    @Transactional
    public SanctionedTeamResponse addSanction(AuthenticatedUser actor, Long tournamentId, Long teamId, String note) {
        Tournament tournament = tournamentService.requireTournamentForUpdate(tournamentId);
        requireFinished(tournament);
        Team team = participants(tournamentId, allMatches(tournamentId)).get(teamId);
        if (team == null) {
            throw new BusinessRuleException("El equipo '" + teamService.requireTeam(teamId).getName()
                    + "' no participó en el torneo «" + tournament.getName() + "».");
        }
        if (sanctions.existsByTournamentIdAndTeamId(tournamentId, teamId)) {
            throw new BusinessRuleException(SANCTION_DUPLICATE.formatted(team.getName()));
        }
        SanctionedTeam sanction = SanctionedTeam.builder()
                .tournamentId(tournamentId)
                .teamId(teamId)
                .teamName(team.getName())
                .note(note == null || note.isBlank() ? null : note.trim())
                .createdBy(actor.id())
                .build();
        List<TeamRosterEntry> roster = teamService.rosterOf(teamId);
        roster.forEach(entry -> sanction.addMember(SanctionedTeamMember.builder()
                .userId(entry.user().getId())
                .fullName(entry.user().getFullName())
                .captain(entry.captain())
                .build()));
        SanctionedTeam saved;
        try {
            // Flushed now so a concurrent duplicate fails here, on the unique key, with the same message.
            saved = sanctions.saveAndFlush(sanction);
        } catch (DataIntegrityViolationException ex) {
            if (DataIntegrity.violates(ex, UNIQUE_SANCTION)) {
                throw new BusinessRuleException(SANCTION_DUPLICATE.formatted(team.getName()));
            }
            throw ex;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sanctionId", saved.getId());
        details.put("teamId", teamId);
        details.put("teamName", team.getName());
        details.put("members", roster.size());
        if (saved.getNote() != null) {
            details.put("note", saved.getNote());
        }
        auditService.record(actor.id(), AuditAction.TOURNAMENT_TEAM_SANCTIONED, TOURNAMENT_ENTITY_TYPE, tournamentId,
                details);
        return toResponse(saved);
    }

    @Transactional
    public void removeSanction(AuthenticatedUser actor, Long tournamentId, Long sanctionId) {
        Tournament tournament = tournamentService.requireTournamentForUpdate(tournamentId);
        requireFinished(tournament);
        SanctionedTeam sanction = sanctions.findByIdAndTournamentId(sanctionId, tournamentId)
                .orElseThrow(() -> NotFoundException.of("el equipo sancionado", sanctionId));
        sanctions.delete(sanction);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sanctionId", sanctionId);
        details.put("teamId", sanction.getTeamId());
        details.put("teamName", sanction.getTeamName());
        auditService.record(actor.id(), AuditAction.TOURNAMENT_SANCTION_REMOVED, TOURNAMENT_ENTITY_TYPE, tournamentId,
                details);
    }

    // --- podium photos (ADMIN / ORGANIZER) -------------------------------------------------------

    /** Sets or replaces the photo of a podium position; the replaced binary is deleted after commit. */
    @Transactional
    public PodiumPhotoResponse replacePodiumPhoto(AuthenticatedUser actor, Long tournamentId, int position,
                                                  MultipartFile file) {
        requirePosition(position);
        Tournament tournament = tournamentService.requireTournamentForUpdate(tournamentId);
        requireFinished(tournament);
        TournamentRecords.PodiumPlace place = podiumOf(tournamentId, allMatches(tournamentId)).place(position)
                .orElseThrow(() -> new BusinessRuleException("El torneo «" + tournament.getName()
                        + "» no tiene un equipo en el " + position + "° puesto."));
        String fileId = fileStorage.store(FileUpload.from(file), FileKind.IMAGE, FileOwner.podium(tournamentId));
        PodiumPhoto photo = podiumPhotos.findByTournamentIdAndPosition(tournamentId, position).orElse(null);
        String previous = photo == null ? null : photo.getFileId();
        if (photo == null) {
            photo = PodiumPhoto.builder().tournamentId(tournamentId).position(position).build();
        }
        photo.setTeamId(place.teamId());
        photo.setFileId(fileId);
        photo.setUploadedBy(actor.id());
        podiumPhotos.save(photo);
        fileDeletion.deleteAfterCommit(previous);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("position", position);
        details.put("teamId", place.teamId());
        details.put("fileId", fileId);
        if (previous != null) {
            details.put("previousFileId", previous);
        }
        auditService.record(actor.id(), AuditAction.PODIUM_PHOTO_UPDATED, TOURNAMENT_ENTITY_TYPE, tournamentId, details);
        return new PodiumPhotoResponse(position, place.teamId(), fileId);
    }

    @Transactional
    public void removePodiumPhoto(AuthenticatedUser actor, Long tournamentId, int position) {
        requirePosition(position);
        Tournament tournament = tournamentService.requireTournamentForUpdate(tournamentId);
        requireFinished(tournament);
        PodiumPhoto photo = podiumPhotos.findByTournamentIdAndPosition(tournamentId, position)
                .orElseThrow(() -> new BusinessRuleException("El " + position + "° puesto no tiene foto."));
        podiumPhotos.delete(photo);
        fileDeletion.deleteAfterCommit(photo.getFileId());
        auditService.record(actor.id(), AuditAction.PODIUM_PHOTO_REMOVED, TOURNAMENT_ENTITY_TYPE, tournamentId,
                Map.of("position", position, "previousFileId", photo.getFileId()));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private List<Match> allMatches(Long tournamentId) {
        return matches.findByTournamentIdOrderByScheduledAtAscIdAsc(tournamentId);
    }

    private TournamentRecords.Podium podiumOf(Long tournamentId, List<Match> all) {
        return TournamentRecords.podium(all, standingsService.rows(tournamentId));
    }

    private List<TournamentRecords.Scorer> scorerRows(Long tournamentId) {
        return events.findTopScorers(tournamentId).stream()
                .map(row -> new TournamentRecords.Scorer((Long) row[0], (String) row[1], (Long) row[2], (String) row[3],
                        ((Number) row[4]).longValue()))
                .toList();
    }

    /** The teams that took part: an APPROVED registration or at least one match, by id. */
    private Map<Long, Team> participants(Long tournamentId, List<Match> all) {
        Map<Long, Team> teams = new LinkedHashMap<>();
        for (Match match : all) {
            teams.putIfAbsent(match.getHomeTeam().getId(), match.getHomeTeam());
            teams.putIfAbsent(match.getAwayTeam().getId(), match.getAwayTeam());
        }
        for (Long teamId : tournamentService.approvedTeamIds(tournamentId)) {
            teams.computeIfAbsent(teamId, teamService::requireTeam);
        }
        return teams;
    }

    private static void requireFinished(Tournament tournament) {
        if (tournament.getStatus() != TournamentStatus.FINISHED) {
            throw new BusinessRuleException("El historial solo está disponible para torneos finalizados; el estado de «"
                    + tournament.getName() + "» es «" + tournament.getStatus().label() + "».");
        }
    }

    private static void requirePosition(int position) {
        if (position < PodiumPhoto.MIN_POSITION || position > PodiumPhoto.MAX_POSITION) {
            throw new InvalidRequestException("La posición del podio debe ser 1, 2 o 3.");
        }
    }

    private static String sourceName(TournamentRecords.Podium podium) {
        return podium.source() == null ? null : podium.source().name();
    }

    private static SanctionedTeamResponse toResponse(SanctionedTeam sanction) {
        return new SanctionedTeamResponse(sanction.getId(), sanction.getTeamId(), sanction.getTeamName(),
                sanction.getNote(), sanction.getCreatedAt(), sanction.getMembers().stream()
                .sorted(Comparator.comparing((SanctionedTeamMember member) -> !member.isCaptain())
                        .thenComparing(SanctionedTeamMember::getFullName, String.CASE_INSENSITIVE_ORDER))
                .map(member -> new SanctionedTeamResponse.Member(member.getFullName(), member.isCaptain()))
                .toList());
    }
}
