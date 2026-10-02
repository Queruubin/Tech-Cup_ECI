package edu.escuelaing.techcup.tournaments.application;

import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.InvalidRequestException;
import edu.escuelaing.techcup.shared.exception.NotFoundException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.storage.FileDeletionScheduler;
import edu.escuelaing.techcup.shared.storage.FileKind;
import edu.escuelaing.techcup.shared.storage.FileOwner;
import edu.escuelaing.techcup.shared.storage.FileStorage;
import edu.escuelaing.techcup.shared.storage.FileUpload;
import edu.escuelaing.techcup.tournaments.api.dto.CreateTournamentRequest;
import edu.escuelaing.techcup.tournaments.api.dto.TournamentResponse;
import edu.escuelaing.techcup.tournaments.api.dto.UpdateTournamentRequest;
import edu.escuelaing.techcup.tournaments.api.dto.VenueResponse;
import edu.escuelaing.techcup.tournaments.domain.Registration;
import edu.escuelaing.techcup.tournaments.domain.RegistrationStatus;
import edu.escuelaing.techcup.tournaments.domain.Tournament;
import edu.escuelaing.techcup.tournaments.domain.TournamentStatus;
import edu.escuelaing.techcup.tournaments.domain.Venue;
import edu.escuelaing.techcup.tournaments.infrastructure.RegistrationRepository;
import edu.escuelaing.techcup.tournaments.infrastructure.TournamentRepository;
import edu.escuelaing.techcup.tournaments.infrastructure.VenueRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Tournament use cases (spec 7.4). Rules enforced here:
 * <ul>
 *   <li>Created in {@link TournamentStatus#DRAFT}. A DRAFT may be edited freely and deleted; an
 *       ACTIVE tournament may only have its dates changed (registration deadline, start, end).</li>
 *   <li>Dates must be coherent: registration deadline &le; start date &le; end date.</li>
 *   <li>Activate (DRAFT &rarr; ACTIVE) requires an uploaded rulebook and at least one venue.</li>
 *   <li>Start (ACTIVE &rarr; IN_PROGRESS) is allowed from the start date up to the end date,
 *       with at least {@value #MIN_TEAMS_TO_START} approved registrations; registrations still
 *       under review are cancelled at that moment, since nothing can be approved afterwards.</li>
 *   <li>Finish (IN_PROGRESS &rarr; FINISHED) is allowed once the end date has been reached or the
 *       FINAL match has been played, and never while a match is still SCHEDULED, since no result
 *       can be recorded afterwards ({@link FinalMatchPort}).</li>
 *   <li>The rulebook is a PDF and venues carry an optional image; both go through the
 *       {@link FileStorage} port and the binaries they replace or orphan are deleted after the
 *       transaction commits. Venues may not be touched once the tournament is FINISHED.</li>
 * </ul>
 * The shape of the lifecycle itself is owned by the {@link TournamentStatus} State machine; this
 * service only adds the guards that need collaborators. Every mutation is audited.
 */
@Service
public class TournamentService {

    static final String ENTITY_TYPE = "TOURNAMENT";
    /** Plausible calendar range; anything outside it is a typo and would overflow the database. */
    static final int MIN_YEAR = 2000;
    static final int MAX_YEAR = 2100;
    static final String VENUE_ENTITY_TYPE = "VENUE";
    static final String REGISTRATION_ENTITY_TYPE = "REGISTRATION";
    static final int MIN_TEAMS_TO_START = 2;
    static final String AUTO_CANCEL_NOTE =
            "Inscripción cancelada automáticamente: el torneo inició sin que fuera revisada.";

    /**
     * Which live tournament the platform shows as "current", in order of precedence: one being
     * played always wins over one still taking registrations, even when the latter starts later.
     */
    private static final List<TournamentStatus> CURRENT_STATUS_PRECEDENCE =
            List.of(TournamentStatus.IN_PROGRESS, TournamentStatus.ACTIVE);

    private final TournamentRepository tournaments;
    private final VenueRepository venues;
    private final RegistrationRepository registrations;
    private final UserService userService;
    private final FileStorage fileStorage;
    private final FileDeletionScheduler fileDeletion;
    private final FinalMatchPort finalMatch;
    private final AuditService auditService;
    private final Clock clock;

    public TournamentService(TournamentRepository tournaments, VenueRepository venues,
                             RegistrationRepository registrations, UserService userService,
                             FileStorage fileStorage, FileDeletionScheduler fileDeletion, FinalMatchPort finalMatch,
                             AuditService auditService, Clock clock) {
        this.tournaments = tournaments;
        this.venues = venues;
        this.registrations = registrations;
        this.userService = userService;
        this.fileStorage = fileStorage;
        this.fileDeletion = fileDeletion;
        this.finalMatch = finalMatch;
        this.auditService = auditService;
        this.clock = clock;
    }

    // --- queries ---------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<TournamentResponse> list() {
        return tournaments.findAllByOrderByStartDateDescIdDesc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public TournamentResponse get(Long id) {
        return toResponse(requireTournament(id));
    }

    /**
     * The tournament the platform is showing today: the latest IN_PROGRESS one, or else the latest
     * ACTIVE one (see {@link #CURRENT_STATUS_PRECEDENCE}).
     */
    @Transactional(readOnly = true)
    public Optional<TournamentResponse> findCurrent() {
        return currentTournament().map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Optional<Tournament> currentTournament() {
        for (TournamentStatus status : CURRENT_STATUS_PRECEDENCE) {
            Optional<Tournament> found = tournaments.findFirstByStatusOrderByStartDateDescIdDesc(status);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public Tournament requireTournament(Long id) {
        return tournaments.findById(id).orElseThrow(() -> NotFoundException.of("el torneo", id));
    }

    /**
     * Loads the tournament with a database write lock; for use cases that count something about
     * it and then create rows, so two concurrent requests cannot both pass the count.
     */
    @Transactional
    public Tournament requireTournamentForUpdate(Long id) {
        return tournaments.findByIdForUpdate(id).orElseThrow(() -> NotFoundException.of("el torneo", id));
    }

    @Transactional(readOnly = true)
    public List<VenueResponse> venuesOf(Long tournamentId) {
        return venues.findByTournamentIdOrderByIdAsc(tournamentId).stream().map(VenueResponse::from).toList();
    }

    /** Ids of the approved teams, in registration order. Used by the fixture generator. */
    @Transactional(readOnly = true)
    public List<Long> approvedTeamIds(Long tournamentId) {
        return registrations.findTeamIdsByStatus(tournamentId, RegistrationStatus.APPROVED);
    }

    // --- lifecycle -------------------------------------------------------------------------

    @Transactional
    public TournamentResponse create(AuthenticatedUser actor, CreateTournamentRequest request) {
        validateDates(request.registrationDeadline(), request.startDate(), request.endDate());
        Tournament tournament = tournaments.save(Tournament.builder()
                .name(request.name().trim())
                .startDate(request.startDate())
                .endDate(request.endDate())
                .registrationDeadline(request.registrationDeadline())
                .maxTeams(request.maxTeams())
                .fee(request.fee())
                .status(TournamentStatus.DRAFT)
                .createdBy(userService.getUser(actor.id()))
                .build());
        auditService.record(actor.id(), AuditAction.TOURNAMENT_CREATED, ENTITY_TYPE, tournament.getId(),
                Map.of("name", tournament.getName(), "startDate", tournament.getStartDate().toString()));
        return toResponse(tournament);
    }

    /**
     * Partial update. Every field may change while the tournament is a DRAFT; once ACTIVE only
     * the three dates may still be adjusted (still validated against each other).
     */
    @Transactional
    public TournamentResponse update(AuthenticatedUser actor, Long id, UpdateTournamentRequest request) {
        Tournament tournament = requireTournament(id);
        boolean draft = tournament.getStatus().isEditable();
        if (!draft && tournament.getStatus() != TournamentStatus.ACTIVE) {
            throw new BusinessRuleException("Un torneo solo se puede modificar mientras esté en borrador o activo; "
                    + "su estado actual es «" + tournament.getStatus().label() + "».");
        }
        if (!draft && changesNonDateFields(request)) {
            throw new BusinessRuleException("Un torneo activo solo permite cambiar sus fechas; el nombre, el cupo "
                    + "y el valor de la inscripción solo se pueden modificar mientras esté en borrador.");
        }

        Map<String, Object> changes = new HashMap<>();
        if (request.name() != null && !request.name().isBlank()) {
            tournament.setName(request.name().trim());
            changes.put("name", tournament.getName());
        }
        if (request.startDate() != null) {
            tournament.setStartDate(request.startDate());
            changes.put("startDate", request.startDate().toString());
        }
        if (request.endDate() != null) {
            tournament.setEndDate(request.endDate());
            changes.put("endDate", request.endDate().toString());
        }
        if (request.registrationDeadline() != null) {
            tournament.setRegistrationDeadline(request.registrationDeadline());
            changes.put("registrationDeadline", request.registrationDeadline().toString());
        }
        if (request.maxTeams() != null) {
            tournament.setMaxTeams(request.maxTeams());
            changes.put("maxTeams", request.maxTeams());
        }
        if (request.fee() != null) {
            tournament.setFee(request.fee());
            changes.put("fee", request.fee().toPlainString());
        }
        if (changes.isEmpty()) {
            throw new BusinessRuleException("No hay nada que actualizar: indique al menos un campo.");
        }
        validateDates(tournament.getRegistrationDeadline(), tournament.getStartDate(), tournament.getEndDate());

        auditService.record(actor.id(), AuditAction.TOURNAMENT_UPDATED, ENTITY_TYPE, id, changes);
        return toResponse(tournament);
    }

    /** Deletes a DRAFT together with its binaries (rulebook and venue images) once the row is gone. */
    @Transactional
    public void delete(AuthenticatedUser actor, Long id) {
        Tournament tournament = requireTournament(id);
        requireEditable(tournament);
        fileDeletion.deleteAfterCommit(tournament.getRulebookFileId());
        tournament.getVenues().forEach(venue -> fileDeletion.deleteAfterCommit(venue.getImageFileId()));
        tournaments.delete(tournament);
        auditService.record(actor.id(), AuditAction.TOURNAMENT_DELETED, ENTITY_TYPE, id,
                Map.of("name", tournament.getName()));
    }

    /** DRAFT &rarr; ACTIVE. Requires the rulebook and at least one venue so teams know what they join. */
    @Transactional
    public TournamentResponse activate(AuthenticatedUser actor, Long id) {
        Tournament tournament = requireTournament(id);
        if (!tournament.hasRulebook()) {
            throw new BusinessRuleException("Cargue el reglamento en PDF antes de activar el torneo.");
        }
        if (venues.findByTournamentIdOrderByIdAsc(id).isEmpty()) {
            throw new BusinessRuleException("Registre al menos una cancha antes de activar el torneo.");
        }
        tournament.moveTo(TournamentStatus.ACTIVE);
        auditService.record(actor.id(), AuditAction.TOURNAMENT_ACTIVATED, ENTITY_TYPE, id);
        return toResponse(tournament);
    }

    /**
     * ACTIVE &rarr; IN_PROGRESS, between the start date and the end date (inclusive) and with
     * enough approved teams. Registrations still under review are cancelled with a note: once the
     * tournament is in progress nothing can be approved any more.
     */
    @Transactional
    public TournamentResponse start(AuthenticatedUser actor, Long id) {
        // Locked like approve/remove/register: a registration removed, approved or created
        // concurrently must be seen by the approved-teams check, not slip past it.
        Tournament tournament = requireTournamentForUpdate(id);
        LocalDate today = LocalDate.now(clock);
        if (today.isBefore(tournament.getStartDate())) {
            throw new BusinessRuleException("El torneo solo se puede iniciar a partir de su fecha de inicio ("
                    + tournament.getStartDate() + "); hoy es " + today + ".");
        }
        if (today.isAfter(tournament.getEndDate())) {
            throw new BusinessRuleException("El torneo ya no se puede iniciar: su fecha de cierre ("
                    + tournament.getEndDate() + ") ya pasó.");
        }
        long approved = registrations.countByTournamentIdAndStatus(id, RegistrationStatus.APPROVED);
        if (approved < MIN_TEAMS_TO_START) {
            throw new BusinessRuleException("Se requieren al menos " + MIN_TEAMS_TO_START
                    + " inscripciones aprobadas para iniciar el torneo (por ahora hay " + approved + ").");
        }
        tournament.moveTo(TournamentStatus.IN_PROGRESS);

        List<Registration> pending = registrations.findByTournamentIdAndStatusOrderByIdAsc(id,
                RegistrationStatus.UNDER_REVIEW);
        if (!pending.isEmpty()) {
            AppUser reviewer = userService.getUser(actor.id());
            for (Registration registration : pending) {
                registration.moveTo(RegistrationStatus.CANCELLED, reviewer, AUTO_CANCEL_NOTE);
                auditService.record(actor.id(), AuditAction.REGISTRATION_CANCELLED, REGISTRATION_ENTITY_TYPE,
                        registration.getId(), Map.of("teamId", registration.getTeam().getId(),
                                "tournamentId", id, "reason", "TOURNAMENT_STARTED"));
            }
        }

        auditService.record(actor.id(), AuditAction.TOURNAMENT_STARTED, ENTITY_TYPE, id,
                Map.of("approvedTeams", approved, "cancelledRegistrations", pending.size()));
        return toResponse(tournament);
    }

    /**
     * IN_PROGRESS &rarr; FINISHED. Allowed once the end date has been reached, or earlier when the
     * FINAL match has already been played (the competition is over, whatever the calendar says).
     * Refused while any match is still SCHEDULED: the organizer must record or cancel it first,
     * because a FINISHED tournament accepts no more results. The tournament row is locked (like
     * advance, undo and fixture generation do), so a concurrent advance or result cannot slip in
     * between the checks and the status change.
     */
    @Transactional
    public TournamentResponse finish(AuthenticatedUser actor, Long id) {
        Tournament tournament = requireTournamentForUpdate(id);
        LocalDate today = LocalDate.now(clock);
        boolean endDateReached = !today.isBefore(tournament.getEndDate());
        boolean finalPlayed = finalMatch.isFinalMatchPlayed(id);
        if (!endDateReached && !finalPlayed) {
            throw new BusinessRuleException("El torneo no se puede finalizar antes de su fecha de cierre ("
                    + tournament.getEndDate() + ") a menos que ya se haya jugado la final.");
        }
        if (finalMatch.hasScheduledMatches(id)) {
            throw new BusinessRuleException("El torneo todavía tiene partidos programados; registre su resultado "
                    + "o cancélelos antes de finalizarlo.");
        }
        tournament.moveTo(TournamentStatus.FINISHED);
        auditService.record(actor.id(), AuditAction.TOURNAMENT_FINISHED, ENTITY_TYPE, id,
                Map.of("endDateReached", endDateReached, "finalPlayed", finalPlayed));
        return toResponse(tournament);
    }

    // --- rulebook and venues ---------------------------------------------------------------

    /** Stores the new PDF and, once the change is committed, deletes the one it replaces. */
    @Transactional
    public TournamentResponse uploadRulebook(AuthenticatedUser actor, Long id, MultipartFile file) {
        Tournament tournament = requireTournament(id);
        if (tournament.getStatus() != TournamentStatus.DRAFT && tournament.getStatus() != TournamentStatus.ACTIVE) {
            throw new BusinessRuleException("El reglamento solo se puede cargar mientras el torneo esté en borrador o activo.");
        }
        String previous = tournament.getRulebookFileId();
        String fileId = fileStorage.store(FileUpload.from(file), FileKind.PDF, FileOwner.rulebook(id));
        tournament.setRulebookFileId(fileId);
        fileDeletion.deleteAfterCommit(previous);
        auditService.record(actor.id(), AuditAction.RULEBOOK_UPLOADED, ENTITY_TYPE, id,
                Map.of("rulebookFileId", fileId));
        return toResponse(tournament);
    }

    @Transactional
    public VenueResponse createVenue(AuthenticatedUser actor, Long id, String name, String description,
                                     MultipartFile image) {
        Tournament tournament = requireTournament(id);
        requireNotFinished(tournament, "las canchas");
        String imageFileId = image == null || image.isEmpty()
                ? null
                : fileStorage.store(FileUpload.from(image), FileKind.IMAGE, FileOwner.venue(id));
        Venue venue = Venue.builder()
                .name(name.trim())
                .description(description == null || description.isBlank() ? null : description.trim())
                .imageFileId(imageFileId)
                .build();
        tournament.addVenue(venue);
        venue = venues.save(venue);
        auditService.record(actor.id(), AuditAction.VENUE_CREATED, VENUE_ENTITY_TYPE, venue.getId(),
                Map.of("tournamentId", id, "name", venue.getName()));
        return VenueResponse.from(venue);
    }

    @Transactional
    public void deleteVenue(AuthenticatedUser actor, Long id, Long venueId) {
        Tournament tournament = requireTournament(id);
        requireNotFinished(tournament, "las canchas");
        Venue venue = venues.findById(venueId)
                .filter(candidate -> candidate.getTournament().getId().equals(id))
                .orElseThrow(() -> NotFoundException.of("la cancha", venueId));
        tournament.removeVenue(venue);
        venues.delete(venue);
        fileDeletion.deleteAfterCommit(venue.getImageFileId());
        auditService.record(actor.id(), AuditAction.VENUE_DELETED, VENUE_ENTITY_TYPE, venueId,
                Map.of("tournamentId", id, "name", venue.getName()));
    }

    // --- helpers ---------------------------------------------------------------------------

    public TournamentResponse toResponse(Tournament tournament) {
        long approved = tournament.getId() == null
                ? 0L
                : registrations.countByTournamentIdAndStatus(tournament.getId(), RegistrationStatus.APPROVED);
        return new TournamentResponse(
                tournament.getId(),
                tournament.getName(),
                tournament.getStartDate(),
                tournament.getEndDate(),
                tournament.getRegistrationDeadline(),
                tournament.getMaxTeams(),
                tournament.getFee(),
                tournament.getStatus(),
                tournament.getRulebookFileId(),
                tournament.getVenues().stream().map(VenueResponse::from).toList(),
                approved);
    }

    private static boolean changesNonDateFields(UpdateTournamentRequest request) {
        return (request.name() != null && !request.name().isBlank())
                || request.maxTeams() != null
                || request.fee() != null;
    }

    private static void requireEditable(Tournament tournament) {
        if (!tournament.getStatus().isEditable()) {
            throw new BusinessRuleException("Un torneo solo se puede eliminar mientras esté en borrador; "
                    + "su estado actual es «" + tournament.getStatus().label() + "».");
        }
    }

    /** @param what the Spanish noun phrase for what is being protected, e.g. {@code "las canchas"} */
    private static void requireNotFinished(Tournament tournament, String what) {
        if (tournament.getStatus().isFinished()) {
            throw new BusinessRuleException("No se pueden modificar " + what + " de un torneo finalizado.");
        }
    }

    static void validateDates(LocalDate registrationDeadline, LocalDate startDate, LocalDate endDate) {
        for (LocalDate date : new LocalDate[]{registrationDeadline, startDate, endDate}) {
            if (date.getYear() < MIN_YEAR || date.getYear() > MAX_YEAR) {
                throw new InvalidRequestException("Las fechas del torneo deben estar entre los años "
                        + MIN_YEAR + " y " + MAX_YEAR + ".");
            }
        }
        if (startDate.isAfter(endDate)) {
            throw new BusinessRuleException("La fecha de inicio no puede ser posterior a la fecha de cierre.");
        }
        if (registrationDeadline.isAfter(startDate)) {
            throw new BusinessRuleException("La fecha límite de inscripción no puede ser posterior a la fecha de inicio.");
        }
    }
}
