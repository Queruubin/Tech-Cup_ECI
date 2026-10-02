package edu.escuelaing.techcup.competition.application;

import edu.escuelaing.techcup.competition.api.dto.LineupRequest;
import edu.escuelaing.techcup.competition.api.dto.LineupResponse;
import edu.escuelaing.techcup.competition.domain.Lineup;
import edu.escuelaing.techcup.competition.domain.LineupPlayer;
import edu.escuelaing.techcup.competition.domain.Match;
import edu.escuelaing.techcup.competition.domain.MatchStatus;
import edu.escuelaing.techcup.competition.infrastructure.LineupRepository;
import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.ForbiddenOperationException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.security.Roles;
import edu.escuelaing.techcup.teams.application.MemberProfilePort;
import edu.escuelaing.techcup.teams.application.TeamService;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.teams.domain.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lineup use cases (spec 7.5 "Alineaciones"). Rules enforced here:
 * <ul>
 *   <li>Only the captain of one of the two teams may submit that team's lineup, and only before
 *       the kick-off time of a SCHEDULED match.</li>
 *   <li>Exactly {@value Lineup#STARTERS} distinct starters, all of them current members of the
 *       team; every remaining member is stored as a substitute.</li>
 *   <li>The formation defaults to {@code F_2_3_1} when the captain does not choose one.</li>
 *   <li>A lineup is visible to the members of that team, to organizers, to administrators and to
 *       the referee appointed for the match — nobody else, so a rival cannot scout it.</li>
 * </ul>
 * Audited as LINEUP_SAVED.
 */
@Service
public class LineupService {

    static final String ENTITY_TYPE = "LINEUP";

    private final LineupRepository lineups;
    private final MatchService matchService;
    private final TeamService teamService;
    private final UserService userService;
    private final MemberProfilePort memberProfiles;
    private final AuditService auditService;
    private final Clock clock;

    public LineupService(LineupRepository lineups, MatchService matchService, TeamService teamService,
                         UserService userService, MemberProfilePort memberProfiles, AuditService auditService,
                         Clock clock) {
        this.lineups = lineups;
        this.matchService = matchService;
        this.teamService = teamService;
        this.userService = userService;
        this.memberProfiles = memberProfiles;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public LineupResponse save(AuthenticatedUser actor, Long matchId, LineupRequest request) {
        // Same lock as a team change, so a lineup can never be written for a team that a
        // concurrent edit is removing from the match.
        Match match = matchService.requireMatchLockingTournament(matchId);
        Team team = requireCaptainOfAPlayingTeam(actor, match);
        requireBeforeKickOff(match);

        Set<Long> starterIds = new LinkedHashSet<>(request.starterIds());
        if (starterIds.size() != request.starterIds().size()) {
            throw new BusinessRuleException("La alineación titular tiene jugadores repetidos.");
        }
        if (starterIds.size() != Lineup.STARTERS) {
            throw new BusinessRuleException("Se requieren exactamente " + Lineup.STARTERS + " titulares (se recibieron "
                    + starterIds.size() + ").");
        }
        List<Long> outsiders = starterIds.stream().filter(id -> !team.hasMember(id)).toList();
        if (!outsiders.isEmpty()) {
            throw new BusinessRuleException("Estos jugadores no son integrantes del equipo '" + team.getName() + "': "
                    + outsiders + ".");
        }

        Lineup lineup = lineups.findByMatchIdAndTeamId(matchId, team.getId())
                .orElseGet(() -> Lineup.builder().match(match).team(team).build());
        lineup.setFormation(request.formationOrDefault());

        List<LineupPlayer> players = new ArrayList<>();
        for (TeamMember member : team.getMembers()) {
            AppUser player = userService.getUser(member.userId());
            players.add(LineupPlayer.of(player, starterIds.contains(member.userId())));
        }
        lineup.replacePlayers(players);
        lineup = lineups.save(lineup);

        auditService.record(actor.id(), AuditAction.LINEUP_SAVED, ENTITY_TYPE, lineup.getId(),
                Map.of("matchId", matchId, "teamId", team.getId(),
                        "formation", lineup.getFormation().name()));
        return toResponse(lineup);
    }

    @Transactional(readOnly = true)
    public Optional<LineupResponse> find(AuthenticatedUser actor, Long matchId, Long teamId) {
        Match match = matchService.requireMatch(matchId);
        requireCanSeeLineup(actor, match, teamId);
        return lineups.findByMatchIdAndTeamId(matchId, teamId).map(this::toResponse);
    }

    // --- helpers ---------------------------------------------------------------------------

    private Team requireCaptainOfAPlayingTeam(AuthenticatedUser actor, Match match) {
        for (Team candidate : List.of(match.getHomeTeam(), match.getAwayTeam())) {
            if (candidate.isCaptain(actor.id())) {
                return candidate;
            }
        }
        throw new ForbiddenOperationException("Solo el capitán de uno de los dos equipos de este partido puede "
                + "enviar una alineación.");
    }

    private void requireBeforeKickOff(Match match) {
        if (match.getStatus() != MatchStatus.SCHEDULED) {
            throw new BusinessRuleException("No se puede modificar la alineación de un partido " + match.getStatus().label() + ".");
        }
        if (match.getScheduledAt() != null && !match.getScheduledAt().isAfter(Instant.now(clock))) {
            throw new BusinessRuleException("La alineación se debe enviar antes de que inicie el partido ("
                    + match.getScheduledAt() + ").");
        }
    }

    private void requireCanSeeLineup(AuthenticatedUser actor, Match match, Long teamId) {
        if (!match.involves(teamId)) {
            throw new BusinessRuleException("El equipo " + teamId + " no juega este partido.");
        }
        boolean privileged = actor.isAdmin()
                || actor.hasRole(Roles.ORGANIZER)
                || (match.getReferee() != null && match.getReferee().getId().equals(actor.id()));
        if (privileged) {
            return;
        }
        Team team = teamService.requireTeam(teamId);
        if (!team.hasMember(actor.id())) {
            throw new ForbiddenOperationException("Solo los integrantes del equipo '" + team.getName() + "', un organizador, "
                    + "un administrador o el árbitro del partido pueden ver esta alineación.");
        }
    }

    private LineupResponse toResponse(Lineup lineup) {
        List<Long> playerIds = lineup.getPlayers().stream().map(LineupPlayer::playerId).toList();
        Map<Long, MemberProfilePort.MemberProfile> profiles = memberProfiles.findProfiles(playerIds);
        List<LineupResponse.Player> starters = new ArrayList<>();
        List<LineupResponse.Player> substitutes = new ArrayList<>();
        lineup.getPlayers().forEach(player -> {
            var profile = profiles.get(player.playerId());
            LineupResponse.Player row = new LineupResponse.Player(
                    player.playerId(),
                    player.getPlayer().getFullName(),
                    profile == null ? null : profile.position(),
                    profile == null ? null : profile.jerseyNumber());
            (player.isStarter() ? starters : substitutes).add(row);
        });
        return new LineupResponse(lineup.getMatch().getId(), lineup.getTeam().getId(), lineup.getFormation(),
                List.copyOf(starters), List.copyOf(substitutes));
    }
}
