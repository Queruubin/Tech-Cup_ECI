package edu.escuelaing.techcup.tournaments.application;

import edu.escuelaing.techcup.identity.application.PlayerAgePolicy;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.identity.domain.SchoolRelation;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.ForbiddenOperationException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.teams.application.TeamRosterEntry;
import edu.escuelaing.techcup.teams.application.TeamService;
import edu.escuelaing.techcup.tournaments.domain.Registration;
import edu.escuelaing.techcup.tournaments.domain.RegistrationStatus;
import edu.escuelaing.techcup.tournaments.domain.Tournament;
import edu.escuelaing.techcup.tournaments.infrastructure.RegistrationRepository;
import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spreadsheet of the players of a tournament, for the administrator (insurance, ID checks,
 * eligibility). ADMIN only, in any tournament status (SUSPENDED and FINISHED included).
 * <ul>
 *   <li>Sheet "Aprobados": one row per member of every team with an APPROVED registration.</li>
 *   <li>Sheet "En revisión": the same for UNDER_REVIEW registrations, so the administrator can
 *       check the players before approving. Those rosters are not frozen yet, so the sheet is a
 *       snapshot; it is empty once the tournament has started (pending registrations are then
 *       cancelled). Rejected and cancelled registrations are left out.</li>
 * </ul>
 * Rows are ordered by team name, captain first, then by player name. The export is audited
 * ({@code PLAYERS_EXPORTED}): it carries identity documents, birth dates and phones.
 */
@Service
public class PlayerExportService {

    static final String APPROVED_SHEET = "Aprobados";
    static final String UNDER_REVIEW_SHEET = "En revisión";
    private static final int MAX_SLUG_LENGTH = 60;

    private final TournamentService tournamentService;
    private final RegistrationRepository registrations;
    private final TeamService teamService;
    private final PlayerAgePolicy agePolicy;
    private final PlayerSpreadsheetWriter writer;
    private final AuditService auditService;

    public PlayerExportService(TournamentService tournamentService, RegistrationRepository registrations,
                               TeamService teamService, PlayerAgePolicy agePolicy, PlayerSpreadsheetWriter writer,
                               AuditService auditService) {
        this.tournamentService = tournamentService;
        this.registrations = registrations;
        this.teamService = teamService;
        this.agePolicy = agePolicy;
        this.writer = writer;
        this.auditService = auditService;
    }

    /**
     * @throws ForbiddenOperationException when the actor is not an ADMIN (the endpoint already
     *                                     requires the role; this keeps the rule in the use case)
     * @throws edu.escuelaing.techcup.shared.exception.NotFoundException when the tournament does not exist
     */
    @Transactional
    public PlayerExport export(AuthenticatedUser actor, Long tournamentId) {
        if (actor == null || !actor.isAdmin()) {
            throw new ForbiddenOperationException("Solo un administrador puede descargar los datos de los jugadores.");
        }
        Tournament tournament = tournamentService.requireTournament(tournamentId);
        List<PlayerExportRow> approved = rowsOf(tournamentId, RegistrationStatus.APPROVED);
        List<PlayerExportRow> underReview = rowsOf(tournamentId, RegistrationStatus.UNDER_REVIEW);
        byte[] content = writer.write(List.of(
                new PlayerExportSheet(APPROVED_SHEET, approved),
                new PlayerExportSheet(UNDER_REVIEW_SHEET, underReview)));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("approvedPlayers", approved.size());
        details.put("underReviewPlayers", underReview.size());
        auditService.record(actor.id(), AuditAction.PLAYERS_EXPORTED, TournamentService.ENTITY_TYPE, tournamentId,
                details);
        String filename = "jugadores-" + slug(tournament.getName(), tournamentId) + "." + writer.extension();
        return new PlayerExport(filename, writer.contentType(), content);
    }

    private List<PlayerExportRow> rowsOf(Long tournamentId, RegistrationStatus status) {
        Collator collator = Collator.getInstance(Locale.forLanguageTag("es"));
        collator.setStrength(Collator.SECONDARY);
        List<TeamRosterEntry> entries = new ArrayList<>();
        for (Registration registration : registrations.findByTournamentIdAndStatusOrderByIdAsc(tournamentId, status)) {
            entries.addAll(teamService.rosterOf(registration.getTeam().getId()));
        }
        String statusLabel = capitalize(status.label());
        Comparator<TeamRosterEntry> order = Comparator
                .comparing(TeamRosterEntry::teamName, collator)
                .thenComparing(TeamRosterEntry::teamId)
                .thenComparing(entry -> !entry.captain())
                .thenComparing(entry -> entry.user().getFullName(), collator);
        return entries.stream().sorted(order).map(entry -> toRow(entry, statusLabel)).toList();
    }

    private PlayerExportRow toRow(TeamRosterEntry entry, String statusLabel) {
        AppUser user = entry.user();
        SchoolRelation relation = user.getSchoolRelation();
        return new PlayerExportRow(
                user.getFullName(),
                user.getEmail(),
                user.getPhoneNumber(),
                user.getDocumentType() == null ? null : user.getDocumentType().label(),
                user.getDocumentNumber(),
                user.getBirthDate(),
                user.getBirthDate() == null ? null : agePolicy.ageOn(user.getBirthDate()),
                relation == null ? null : relation.label(),
                user.getAcademicProgram() == null ? null : user.getAcademicProgram().label(),
                relation == SchoolRelation.STUDENT ? user.getSemester() : null,
                entry.teamName(),
                entry.captain(),
                entry.position() == null ? null : entry.position().label(),
                entry.jerseyNumber(),
                statusLabel);
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /**
     * File-name-safe version of the tournament name: accents removed, lower case, anything other
     * than letters and digits collapsed to "-" ("Copa Ñandú 2026-2" becomes "copa-nandu-2026-2"),
     * at most {@value #MAX_SLUG_LENGTH} characters. Falls back to "torneo-{id}" when nothing is left.
     */
    static String slug(String name, Long tournamentId) {
        String ascii = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (ascii.length() > MAX_SLUG_LENGTH) {
            ascii = ascii.substring(0, MAX_SLUG_LENGTH).replaceAll("-+$", "");
        }
        return ascii.isEmpty() ? "torneo-" + tournamentId : ascii;
    }
}
