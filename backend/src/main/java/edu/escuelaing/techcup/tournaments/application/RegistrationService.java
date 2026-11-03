package edu.escuelaing.techcup.tournaments.application;

import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.ForbiddenOperationException;
import edu.escuelaing.techcup.shared.exception.InvalidRequestException;
import edu.escuelaing.techcup.shared.exception.Messages;
import edu.escuelaing.techcup.shared.exception.NotFoundException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.storage.FileDeletionScheduler;
import edu.escuelaing.techcup.shared.storage.FileKind;
import edu.escuelaing.techcup.shared.storage.FileOwner;
import edu.escuelaing.techcup.shared.storage.FileStorage;
import edu.escuelaing.techcup.shared.storage.FileUpload;
import edu.escuelaing.techcup.teams.application.TeamService;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.teams.domain.TeamEligibility;
import edu.escuelaing.techcup.tournaments.api.dto.RegistrationResponse;
import edu.escuelaing.techcup.tournaments.domain.Registration;
import edu.escuelaing.techcup.tournaments.domain.RegistrationStatus;
import edu.escuelaing.techcup.tournaments.domain.Tournament;
import edu.escuelaing.techcup.tournaments.domain.TournamentStatus;
import edu.escuelaing.techcup.tournaments.infrastructure.RegistrationRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Registration use cases (spec 7.4 "Inscripciones"). Rules enforced here:
 * <ul>
 *   <li>Only the captain of an ACTIVE team may register it, and only in a tournament whose status
 *       is ACTIVE, on or before the registration deadline.</li>
 *   <li>The tournament must still have room: approved registrations &lt; {@code maxTeams}.</li>
 *   <li>The team must pass the eligibility rule of the teams module ({@value TeamEligibility#MIN_MEMBERS}
 *       to {@value TeamEligibility#MAX_MEMBERS} members, unique jerseys, program majority, every
 *       member with a sport profile).</li>
 *   <li>A team may hold only one live (UNDER_REVIEW or APPROVED) registration per tournament; a
 *       rejected or cancelled attempt may be replaced by a new one.</li>
 *   <li>Two documents are mandatory, both image or PDF ({@link FileKind#IMAGE_OR_PDF}, magic bytes
 *       checked) and stored through the {@link FileStorage} port: the payment receipt (owner
 *       {@code RECEIPT}) and the evidence that the university form was filled in (owner
 *       {@code FORM_EVIDENCE}); a missing one is a 400. Registrations created before the evidence
 *       existed have none: an APPROVED one stays valid, an UNDER_REVIEW one cannot be approved
 *       until its captain uploads it ({@link #uploadFormEvidence}).</li>
 *   <li>Approve / reject are organizer-only and start from UNDER_REVIEW; approval requires the
 *       tournament to still be ACTIVE, re-checks the capacity under a lock on the tournament
 *       row, because several registrations may be waiting at the same time, and re-runs the
 *       eligibility rule on a team that must still be ACTIVE (the roster is not locked while the
 *       registration is under review). Cancel is captain-only and also starts from UNDER_REVIEW.</li>
 *   <li>Nothing changes while the tournament is SUSPENDED: every use case locks the tournament row
 *       and refuses with {@link Tournament#requireNotSuspended}.</li>
 * </ul>
 * The legal transitions themselves belong to the {@link RegistrationStatus} State machine.
 * Approving a registration is what "locks" a team (see {@code teams.application.TeamLockPort}):
 * the lock is derived from the row written here, so it needs no extra bookkeeping.
 * Audited as REGISTRATION_CREATED / APPROVED / REJECTED / CANCELLED / FORM_EVIDENCE_UPLOADED.
 */
@Service
public class RegistrationService {

    static final String ENTITY_TYPE = "REGISTRATION";

    static final String MISSING_RECEIPT = "Adjunte el comprobante de pago.";
    static final String MISSING_FORM_EVIDENCE = "Adjunte la evidencia del formulario de la Escuela.";
    static final String APPROVAL_WITHOUT_FORM_EVIDENCE = "La inscripción no tiene la evidencia del formulario "
            + "de la Escuela. El capitán debe subirla antes de aprobarla.";

    private static final Set<RegistrationStatus> LIVE_STATUSES =
            Set.of(RegistrationStatus.UNDER_REVIEW, RegistrationStatus.APPROVED);

    private final RegistrationRepository registrations;
    private final TournamentService tournamentService;
    private final TeamService teamService;
    private final UserService userService;
    private final FileStorage fileStorage;
    private final FileDeletionScheduler fileDeletion;
    private final AuditService auditService;
    private final Clock clock;

    public RegistrationService(RegistrationRepository registrations, TournamentService tournamentService,
                               TeamService teamService, UserService userService, FileStorage fileStorage,
                               FileDeletionScheduler fileDeletion, AuditService auditService, Clock clock) {
        this.registrations = registrations;
        this.tournamentService = tournamentService;
        this.teamService = teamService;
        this.userService = userService;
        this.fileStorage = fileStorage;
        this.fileDeletion = fileDeletion;
        this.auditService = auditService;
        this.clock = clock;
    }

    // --- queries ---------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<RegistrationResponse> listByTournament(Long tournamentId) {
        tournamentService.requireTournament(tournamentId);
        return registrations.findByTournamentIdOrderByCreatedAtAscIdAsc(tournamentId).stream()
                .map(RegistrationResponse::from)
                .toList();
    }

    /**
     * The registration of the caller's active team for a tournament, whatever its status. The file
     * references are returned only to the team captain (the one who uploaded them).
     */
    @Transactional(readOnly = true)
    public Optional<RegistrationResponse> findMine(AuthenticatedUser actor, Long tournamentId) {
        return teamService.findActiveTeamOf(actor.id())
                .flatMap(team -> registrations.findFirstByTournamentIdAndTeamIdOrderByIdDesc(tournamentId, team.getId())
                        .map(RegistrationResponse::from)
                        .map(response -> team.isCaptain(actor.id()) ? response : response.withoutReceipt()));
    }

    /** The latest registration of a team for a tournament; used by the home page. */
    @Transactional(readOnly = true)
    public Optional<RegistrationResponse> findByTeam(Long tournamentId, Long teamId) {
        return registrations.findFirstByTournamentIdAndTeamIdOrderByIdDesc(tournamentId, teamId)
                .map(RegistrationResponse::from);
    }

    // --- captain use cases -------------------------------------------------------------------

    @Transactional
    public RegistrationResponse register(AuthenticatedUser actor, Long tournamentId, MultipartFile receipt,
                                         MultipartFile formEvidence) {
        requireAttached(receipt, MISSING_RECEIPT);
        requireAttached(formEvidence, MISSING_FORM_EVIDENCE);
        // Locked so a registration cannot land in a tournament that is being started concurrently
        // (start cancels every UNDER_REVIEW registration and must see this one).
        Tournament tournament = tournamentService.requireTournamentForUpdate(tournamentId);
        tournament.requireNotSuspended("inscribir equipos");
        if (tournament.getStatus() != TournamentStatus.ACTIVE) {
            throw new BusinessRuleException("Solo se aceptan inscripciones mientras el torneo esté activo; "
                    + "su estado actual es «" + tournament.getStatus().label() + "».");
        }
        LocalDate today = LocalDate.now(clock);
        if (today.isAfter(tournament.getRegistrationDeadline())) {
            throw new BusinessRuleException("La fecha límite de inscripción (" + tournament.getRegistrationDeadline()
                    + ") ya pasó.");
        }

        Team team = teamService.findActiveTeamOf(actor.id())
                .orElseThrow(() -> new BusinessRuleException("Usted no pertenece a ningún equipo activo."));
        if (!team.isCaptain(actor.id())) {
            throw new ForbiddenOperationException("Solo el capitán del equipo '" + team.getName()
                    + "' puede inscribirlo en un torneo.");
        }

        registrations.findFirstByTournamentIdAndTeamIdAndStatusInOrderByIdDesc(tournamentId, team.getId(), LIVE_STATUSES)
                .ifPresent(existing -> {
                    throw new BusinessRuleException("El equipo '" + team.getName() + "' ya tiene una inscripción "
                            + existing.getStatus().label() + " para este torneo.");
                });

        requireCapacity(tournament);

        TeamEligibility.Result eligibility = teamService.eligibility(team.getId());
        if (!eligibility.eligible()) {
            throw new BusinessRuleException("El equipo '" + team.getName() + "' no cumple los requisitos: "
                    + String.join(" ", eligibility.problems()));
        }

        String receiptFileId = fileStorage.store(FileUpload.from(receipt), FileKind.IMAGE_OR_PDF,
                FileOwner.receipt(team.getId()));
        String formEvidenceFileId = fileStorage.store(FileUpload.from(formEvidence), FileKind.IMAGE_OR_PDF,
                FileOwner.formEvidence(team.getId()));
        Registration registration = registrations.save(Registration.builder()
                .tournament(tournament)
                .team(team)
                .receiptFileId(receiptFileId)
                .formEvidenceFileId(formEvidenceFileId)
                .status(RegistrationStatus.UNDER_REVIEW)
                .build());

        auditService.record(actor.id(), AuditAction.REGISTRATION_CREATED, ENTITY_TYPE, registration.getId(),
                Map.of("tournamentId", tournamentId, "teamId", team.getId(), "teamName", team.getName()));
        return RegistrationResponse.from(registration);
    }

    /**
     * The team captain adds or replaces the university-form evidence of a registration still
     * UNDER_REVIEW (typically one created before the evidence was required). Only the captain of
     * the registered team (403 otherwise, ADMIN included: the captain is who filled in the form);
     * the tournament row is locked like the other registration writes and a suspended tournament
     * refuses it. The replaced binary is deleted after commit.
     */
    @Transactional
    public RegistrationResponse uploadFormEvidence(AuthenticatedUser actor, Long registrationId, MultipartFile file) {
        Registration registration = requireRegistration(registrationId);
        if (!registration.getTeam().isCaptain(actor.id())) {
            throw new ForbiddenOperationException("Solo el capitán del equipo '"
                    + registration.getTeam().getName() + "' puede subir la evidencia del formulario de la Escuela.");
        }
        requireAttached(file, MISSING_FORM_EVIDENCE);
        requireTournamentNotSuspended(registration, "subir la evidencia del formulario de la Escuela");
        if (registration.getStatus() != RegistrationStatus.UNDER_REVIEW) {
            throw new BusinessRuleException("Solo se puede subir la evidencia del formulario mientras la inscripción "
                    + "está en revisión; esta inscripción está «" + registration.getStatus().label() + "».");
        }
        Long teamId = registration.getTeam().getId();
        String previous = registration.getFormEvidenceFileId();
        String fileId = fileStorage.store(FileUpload.from(file), FileKind.IMAGE_OR_PDF, FileOwner.formEvidence(teamId));
        registration.setFormEvidenceFileId(fileId);
        fileDeletion.deleteAfterCommit(previous);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("teamId", teamId);
        details.put("tournamentId", registration.getTournament().getId());
        details.put("formEvidenceFileId", fileId);
        details.put("replaced", previous != null);
        auditService.record(actor.id(), AuditAction.REGISTRATION_FORM_EVIDENCE_UPLOADED, ENTITY_TYPE,
                registrationId, details);
        return RegistrationResponse.from(registration);
    }

    @Transactional
    public RegistrationResponse cancel(AuthenticatedUser actor, Long registrationId) {
        Registration registration = requireRegistration(registrationId);
        if (!actor.isAdmin() && !registration.getTeam().isCaptain(actor.id())) {
            throw new ForbiddenOperationException("Solo el capitán del equipo '"
                    + registration.getTeam().getName() + "' puede cancelar esta inscripción.");
        }
        requireTournamentNotSuspended(registration, "cancelar inscripciones");
        registration.moveTo(RegistrationStatus.CANCELLED, null, null);
        auditService.record(actor.id(), AuditAction.REGISTRATION_CANCELLED, ENTITY_TYPE, registrationId,
                Map.of("teamId", registration.getTeam().getId()));
        return RegistrationResponse.from(registration);
    }

    // --- organizer use cases -----------------------------------------------------------------

    /**
     * Approves a registration under review. The tournament row is locked first: several
     * registrations may be under review at once and two organizers approving concurrently must
     * not both pass the capacity check.
     */
    @Transactional
    public RegistrationResponse approve(AuthenticatedUser actor, Long registrationId, String note) {
        Registration registration = requireRegistration(registrationId);
        Tournament tournament = tournamentService.requireTournamentForUpdate(registration.getTournament().getId());
        tournament.requireNotSuspended("aprobar inscripciones");
        if (tournament.getStatus() != TournamentStatus.ACTIVE) {
            throw new BusinessRuleException("Solo se pueden aprobar inscripciones mientras el torneo esté activo; "
                    + "su estado actual es «" + tournament.getStatus().label() + "».");
        }
        // Registrations created before the evidence was required: approved ones stay valid, but one
        // still under review needs it first (a non-UNDER_REVIEW one is refused by the state machine).
        if (registration.getStatus() == RegistrationStatus.UNDER_REVIEW && !registration.hasFormEvidence()) {
            throw new BusinessRuleException(APPROVAL_WITHOUT_FORM_EVIDENCE);
        }
        requireCapacity(tournament);
        // Roster changes (leave, remove, accept) lock the team row too: the size re-checked here
        // cannot change before this approval commits and freezes the roster.
        teamService.requireTeamForUpdate(registration.getTeam().getId());
        requireStillEligible(registration.getTeam());
        registration.moveTo(RegistrationStatus.APPROVED, userService.getUser(actor.id()), note);
        // The approval freezes the roster: pending requests and invitations can no longer succeed.
        teamService.closeRecruitment(actor.id(), registration.getTeam().getId());
        auditService.record(actor.id(), AuditAction.REGISTRATION_APPROVED, ENTITY_TYPE, registrationId,
                Map.of("teamId", registration.getTeam().getId(),
                        "tournamentId", registration.getTournament().getId()));
        return RegistrationResponse.from(registration);
    }

    @Transactional
    public RegistrationResponse reject(AuthenticatedUser actor, Long registrationId, String note) {
        Registration registration = requireRegistration(registrationId);
        requireTournamentNotSuspended(registration, "rechazar inscripciones");
        registration.moveTo(RegistrationStatus.REJECTED, userService.getUser(actor.id()), note);
        auditService.record(actor.id(), AuditAction.REGISTRATION_REJECTED, ENTITY_TYPE, registrationId,
                Map.of("teamId", registration.getTeam().getId(),
                        "tournamentId", registration.getTournament().getId()));
        return RegistrationResponse.from(registration);
    }

    /**
     * Removes an APPROVED team from a tournament that has not started yet (ORGANIZER; ADMIN inherits
     * the role). The registration is deleted together with its receipt and form evidence, so the team is no longer
     * locked and may register again while registrations are open; the team and its players are
     * untouched. Once the tournament is IN_PROGRESS the fixture exists and the team can only be
     * handled with the match tools. The tournament row is locked first, like {@code approve}, so the
     * removal cannot race with the tournament starting.
     */
    @Transactional
    public void remove(AuthenticatedUser actor, Long registrationId) {
        Registration registration = requireRegistration(registrationId);
        Tournament tournament = tournamentService.requireTournamentForUpdate(registration.getTournament().getId());
        tournament.requireNotSuspended("retirar equipos");
        if (tournament.getStatus() != TournamentStatus.ACTIVE) {
            throw new BusinessRuleException("Solo se puede eliminar un equipo antes de que el torneo inicie; "
                    + "su estado actual es «" + tournament.getStatus().label() + "».");
        }
        if (registration.getStatus() != RegistrationStatus.APPROVED) {
            throw new BusinessRuleException("Solo se puede eliminar un equipo con la inscripción aprobada; "
                    + "esta inscripción está «" + registration.getStatus().label() + "».");
        }
        Long teamId = registration.getTeam().getId();
        String receiptFileId = registration.getReceiptFileId();
        String formEvidenceFileId = registration.getFormEvidenceFileId();
        registrations.delete(registration);
        fileDeletion.deleteAfterCommit(receiptFileId);
        fileDeletion.deleteAfterCommit(formEvidenceFileId);
        auditService.record(actor.id(), AuditAction.REGISTRATION_CANCELLED, ENTITY_TYPE, registrationId,
                Map.of("teamId", teamId, "tournamentId", tournament.getId(), "reason", "REMOVED_BY_ORGANIZER"));
    }

    // --- helpers ---------------------------------------------------------------------------

    /**
     * Locks the registration's tournament (so the check cannot race a concurrent suspension) and
     * refuses when it is suspended.
     */
    private void requireTournamentNotSuspended(Registration registration, String action) {
        tournamentService.requireTournamentForUpdate(registration.getTournament().getId()).requireNotSuspended(action);
    }

    /** A missing or empty part is a 400 with {@code message}, before anything is locked or stored. */
    private static void requireAttached(MultipartFile file, String message) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException(message);
        }
    }

    private Registration requireRegistration(Long registrationId) {
        return registrations.findById(registrationId)
                .orElseThrow(() -> NotFoundException.of("la inscripción", registrationId));
    }

    /**
     * The eligibility gate runs again at approval time: a registration may sit UNDER_REVIEW for
     * days, and the team lock only starts once it is APPROVED, so in between the roster can shrink
     * below the minimum, lose a profile, or the team itself can be inactivated.
     */
    private void requireStillEligible(Team team) {
        if (!team.isActive()) {
            throw new BusinessRuleException("El equipo '" + team.getName() + "' ya no está activo; "
                    + "no se puede aprobar su inscripción.");
        }
        TeamEligibility.Result eligibility = teamService.eligibility(team.getId());
        if (!eligibility.eligible()) {
            throw new BusinessRuleException("El equipo '" + team.getName() + "' ya no cumple los requisitos "
                    + "para participar: " + String.join(" ", eligibility.problems()));
        }
    }

    private void requireCapacity(Tournament tournament) {
        long approved = registrations.countByTournamentIdAndStatus(tournament.getId(), RegistrationStatus.APPROVED);
        if (approved >= tournament.getMaxTeams()) {
            throw new BusinessRuleException("El torneo ya está completo (" + Messages.plural(tournament.getMaxTeams(), "equipo aprobado",
                    "equipos aprobados") + ").");
        }
    }
}
