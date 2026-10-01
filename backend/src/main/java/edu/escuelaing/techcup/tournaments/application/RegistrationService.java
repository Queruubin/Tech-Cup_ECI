package edu.escuelaing.techcup.tournaments.application;

import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.ForbiddenOperationException;
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
 *   <li>The team must pass the eligibility rule of the teams module (7 to 12 members, unique
 *       jerseys, program majority, every member with a sport profile).</li>
 *   <li>A team may hold only one live (UNDER_REVIEW or APPROVED) registration per tournament; a
 *       rejected or cancelled attempt may be replaced by a new one.</li>
 *   <li>A payment receipt (image or PDF) is mandatory and stored through the {@link FileStorage} port.</li>
 *   <li>Approve / reject are organizer-only and start from UNDER_REVIEW; approval requires the
 *       tournament to still be ACTIVE, re-checks the capacity under a lock on the tournament
 *       row, because several registrations may be waiting at the same time, and re-runs the
 *       eligibility rule on a team that must still be ACTIVE (the roster is not locked while the
 *       registration is under review). Cancel is captain-only and also starts from UNDER_REVIEW.</li>
 * </ul>
 * The legal transitions themselves belong to the {@link RegistrationStatus} State machine.
 * Approving a registration is what "locks" a team (see {@code teams.application.TeamLockPort}):
 * the lock is derived from the row written here, so it needs no extra bookkeeping.
 * Audited as REGISTRATION_CREATED / APPROVED / REJECTED / CANCELLED.
 */
@Service
public class RegistrationService {

    static final String ENTITY_TYPE = "REGISTRATION";

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

    /** The registration of the caller's active team for a tournament, whatever its status. */
    @Transactional(readOnly = true)
    public Optional<RegistrationResponse> findMine(AuthenticatedUser actor, Long tournamentId) {
        return teamService.findActiveTeamOf(actor.id())
                .flatMap(team -> registrations.findFirstByTournamentIdAndTeamIdOrderByIdDesc(tournamentId, team.getId()))
                .map(RegistrationResponse::from);
    }

    /** The latest registration of a team for a tournament; used by the home page. */
    @Transactional(readOnly = true)
    public Optional<RegistrationResponse> findByTeam(Long tournamentId, Long teamId) {
        return registrations.findFirstByTournamentIdAndTeamIdOrderByIdDesc(tournamentId, teamId)
                .map(RegistrationResponse::from);
    }

    // --- captain use cases -------------------------------------------------------------------

    @Transactional
    public RegistrationResponse register(AuthenticatedUser actor, Long tournamentId, MultipartFile receipt) {
        Tournament tournament = tournamentService.requireTournament(tournamentId);
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
        Registration registration = registrations.save(Registration.builder()
                .tournament(tournament)
                .team(team)
                .receiptFileId(receiptFileId)
                .status(RegistrationStatus.UNDER_REVIEW)
                .build());

        auditService.record(actor.id(), AuditAction.REGISTRATION_CREATED, ENTITY_TYPE, registration.getId(),
                Map.of("tournamentId", tournamentId, "teamId", team.getId(), "teamName", team.getName()));
        return RegistrationResponse.from(registration);
    }

    @Transactional
    public RegistrationResponse cancel(AuthenticatedUser actor, Long registrationId) {
        Registration registration = requireRegistration(registrationId);
        if (!actor.isAdmin() && !registration.getTeam().isCaptain(actor.id())) {
            throw new ForbiddenOperationException("Solo el capitán del equipo '"
                    + registration.getTeam().getName() + "' puede cancelar esta inscripción.");
        }
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
        if (tournament.getStatus() != TournamentStatus.ACTIVE) {
            throw new BusinessRuleException("Solo se pueden aprobar inscripciones mientras el torneo esté activo; "
                    + "su estado actual es «" + tournament.getStatus().label() + "».");
        }
        requireCapacity(tournament);
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
        registration.moveTo(RegistrationStatus.REJECTED, userService.getUser(actor.id()), note);
        auditService.record(actor.id(), AuditAction.REGISTRATION_REJECTED, ENTITY_TYPE, registrationId,
                Map.of("teamId", registration.getTeam().getId(),
                        "tournamentId", registration.getTournament().getId()));
        return RegistrationResponse.from(registration);
    }

    /**
     * Removes an APPROVED team from a tournament that has not started yet (ORGANIZER; ADMIN inherits
     * the role). The registration is deleted together with its receipt, so the team is no longer
     * locked and may register again while registrations are open; the team and its players are
     * untouched. Once the tournament is IN_PROGRESS the fixture exists and the team can only be
     * handled with the match tools. The tournament row is locked first, like {@code approve}, so the
     * removal cannot race with the tournament starting.
     */
    @Transactional
    public void remove(AuthenticatedUser actor, Long registrationId) {
        Registration registration = requireRegistration(registrationId);
        Tournament tournament = tournamentService.requireTournamentForUpdate(registration.getTournament().getId());
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
        registrations.delete(registration);
        if (receiptFileId != null) {
            fileDeletion.deleteAfterCommit(receiptFileId);
        }
        auditService.record(actor.id(), AuditAction.REGISTRATION_CANCELLED, ENTITY_TYPE, registrationId,
                Map.of("teamId", teamId, "tournamentId", tournament.getId(), "reason", "REMOVED_BY_ORGANIZER"));
    }

    // --- helpers ---------------------------------------------------------------------------

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
