# TechCup Fútbol — Architecture

Platform for managing the semester football tournament of the Escuela Colombiana de Ingeniería
(Systems, AI, Cybersecurity and Statistics programs). Source requirements: `docs/spec-extracted.md`.

## 1. Decisions

| Topic | Decision | Why |
|---|---|---|
| Topology | **Modular monolith** (one Spring Boot app), NOT microservices | ~100 users, one tournament per semester. The five "functional domains" of the spec become five packages with strict layering. |
| "Orchestrator" | Folded into the monolith: JWT filter, global exception handler, `/api/home` | It is what an API gateway does, minus the network hop. |
| Backend | Java 17, Spring Boot 3.x, Maven (`artifactId: techcup`), Spring Security + JWT (jjwt), Spring Data JPA, Flyway, Bean Validation | Spec requires Spring Boot layered + REST + Maven. |
| Relational DB | PostgreSQL 16 | Spec requirement. |
| Binary files | MongoDB 7 via **GridFS** (profile photos, venue images, payment receipts, rulebook PDF) | Spec lists "Almacenamiento de imágenes: MongoDB". Only binaries live there; every reference is a `file_id` string column in Postgres. |
| Frontend | React 18 + TypeScript, Vite, Tailwind CSS v4, Zustand (session/global), React Router, pnpm | User choice. |
| Local infra | `docker-compose.yml` with postgres + mongo | Dev machine has Docker but no local psql. |
| Design patterns | Hexagonal-light per module (ports/adapters), Strategy (bracket phase generation), State (tournament / registration / match status transitions), Builder (JPA entities via Lombok), Factory (match generation), Observer-ish audit via a dedicated `AuditService` | Spec: "No olvide el uso de patrones de diseño". |
| Languages | Code, comments, docs: English. **UI copy: neutral Spanish** (end users are Spanish-speaking students). | Target audience. |

## 2. Repository layout

```
tech-cup/
  backend/              Spring Boot monolith (Maven)
  frontend/             React + Vite (pnpm)
  docs/                 this file, extracted spec
  docker-compose.yml    postgres + mongo (+ optional backend/frontend profiles)
  README.md
```

## 3. Backend

### 3.1 Package layout (`edu.escuelaing.techcup`)

```
TechcupApplication.java
shared/
  config/         CorsConfig, OpenApiConfig, JacksonConfig, AppProperties, TraceIdFilter
  security/       SecurityConfig, JwtService, JwtAuthenticationFilter, CurrentUser (resolver), Roles
  exception/      GlobalExceptionHandler, ApiError, NotFoundException, ConflictException,
                  ForbiddenOperationException, BusinessRuleException
  audit/          AuditLog (entity), AuditAction (enum), AuditService, AuditRepository, AuditController (admin)
  storage/        FileStorage (port), GridFsFileStorage (adapter), StoredFile, FileController
  home/           HomeController, HomeService
identity/         register, login, logout, roles, inactivate
players/          user profile update, sport profile, join requests, invitations, free-agent search
teams/            teams + members
tournaments/      tournaments, venues, rulebook, registrations (payments)
competition/      matches, lineups, results/events, standings, bracket, stats, referee queries
```

Every domain module uses the same four layers:

```
<module>/
  api/            @RestController + request/response DTOs (records)
  application/    @Service use cases, transactional, enforce business rules, call AuditService
  domain/         JPA entities, enums, pure domain rules (no Spring web imports)
  infrastructure/ Spring Data repositories, external adapters
```

Every public mutating method of an `application/*Service` is `@Transactional`, so a use case that
fails part-way (a result plus its events plus the knockout propagation and the automatic next round,
group/knockout creation, advance/undo-phase,
an invitation acceptance) rolls back entirely; audit rows join the same transaction. The one
exception is `AuthService.login`: it is deliberately not transactional (each repository call and
audit write gets its own short transaction), so a burst of failed logins cannot hold one pooled
connection per attempt while the detached `LOGIN_FAILED` audit asks for a second one.
Tournament-wide use cases serialise on the tournament row (`TournamentService.requireTournamentForUpdate`,
`SELECT ... FOR UPDATE`): group changes and group-match creation, knockout creation, advance,
undo-phase, finish, registration approval, and every match mutation (update, cancel, result, reopen),
which locks the tournament before reading the match.

Rule of thumb: controllers never touch repositories; services never build HTTP responses;
domain never imports `org.springframework.web`. Cross-module calls go through the other
module's `application` service (never its repository).

JPA entities double as domain entities (pragmatic hexagonal). Persistence-agnostic domain
models would double the code for a semester project with no benefit.

### 3.2 Security

- Stateless JWT (HS256), claims: `sub` = user id, `email`, `roles`. Expiry from `app.jwt.expiration-minutes` (default 480).
- `JwtAuthenticationFilter` validates signature + expiry on every request, loads `AppUser` into
  `SecurityContext`. Inactive users are rejected with 401.
- Method security with `@PreAuthorize("hasRole('ORGANIZER')")` etc. Roles: `GUEST, PLAYER, CAPTAIN, ORGANIZER, REFEREE, ADMIN`.
  A user can hold several roles (`user_roles` table). `ADMIN` implies everything (role hierarchy).
- Passwords: BCrypt. Every request that sets a password (register, referee creation, self-service change, admin reset) requires 8–72 characters that also fit in 72 UTF-8 bytes (BCrypt's limit; `@MaxUtf8Bytes`, 400 "La contraseña no puede ocupar más de 72 bytes…") with at least one uppercase letter and one digit (`PasswordRules`); login does not re-validate, so older passwords keep working. `ADMIN_PASSWORD` is not validated against this rule.
- E-mail: any address may register; there is no domain rule for any school relation or role.
- Player age: the PLAYER role is limited to ages `app.player.min-age`..`app.player.max-age`
  (env `APP_PLAYER_MIN_AGE` / `APP_PLAYER_MAX_AGE`, default 5..100, inclusive, full years computed
  with the application `Clock` in `app.time-zone`). Enforced by `identity/application/PlayerAgePolicy`.
- Roles are read from the database on every request (the JWT `roles` claim is informational), so a
  role granted during a request (e.g. CAPTAIN on team creation) applies from the next request on.
- Admin is created at startup by `identity/infrastructure/AdminBootstrap` when no `ADMIN` user exists, from `ADMIN_EMAIL` / `ADMIN_PASSWORD` (dev defaults `admin@escuelaing.edu.co` / `Admin123*`, logged with a warning). The `prod` profile (`ProductionGuard`) refuses to start with the dev admin password, the dev (or a shorter than 32-byte) JWT secret, the dev PostgreSQL password (`DB_PASSWORD=techcup`) or the dev MongoDB credentials (`MONGO_URI` containing `techcup:techcup@`), and logs a WARNING (without failing) when `server.address` is not a loopback address (or is unset) and when `CORS_ORIGINS` contains `localhost`.
- Login is throttled: 5 failures per e-mail in 15 minutes → 429 (per e-mail only, not per client IP, so users behind a shared proxy or NAT never block each other), audited as `LOGIN_FAILED`. Duplicate e-mail/document at registration share one generic message (no account enumeration). The counters are in memory (`LoginAttemptService`); expired keys are purged every 15 minutes (`@Scheduled`, `shared/config/SchedulingConfig`) and also on a recorded failure once more than 10 000 keys are tracked (at most once a minute), so random e-mails cannot grow it without bound.
- Client IP behind the reverse proxy: the `prod` profile uses `server.forward-headers-strategy: native` (Tomcat `RemoteIpValve`). It trusts `X-Forwarded-For` / `X-Forwarded-Proto` only from internal proxies (Tomcat's default `internal-proxies`: 127/8, 10/8, 172.16/12, 192.168/16, 169.254/16, IPv6 loopback/ULA, i.e. the host nginx and the Docker network), takes the right-most untrusted `X-Forwarded-For` entry as the client address, and ignores the RFC 7239 `Forwarded` header. `framework` (Spring's `ForwardedHeaderFilter`) must not be used: it believes `Forwarded` / `X-Forwarded-*` from any client, which would let an attacker choose its own IP and scheme.
- Public (no token): `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/files/{id}`? → **No**, files require auth. Public read-only: `GET /api/tournaments/**` (list, detail, standings, bracket, stats, matches, the history of FINISHED tournaments and its podium photos) so anyone can see the tournament. Everything else authenticated. The SPA has two public routes, `/history` and `/history/:id` (linked from the login page); everything else in the SPA needs a session.
- Logout: audited, and the presented token's `jti` goes into an in-memory `TokenDenylist` until its expiry; the filter rejects denylisted tokens with 401.
- Personal data (`email`, `phoneNumber`, `birthDate`, `documentType`, `documentNumber`) is returned only to the user themself or an ADMIN (`UserService.toResponse(user, viewer)`); organizers get the e-mail and the phone (to contact captains) in `GET /admin/users`, `GET /admin/users/{id}` (staff user detail, `UserService.getForStaff`) and `GET /organizer/referees` only (`UserService.redact(…, keepContact)`). `GET /users/{id}` gives anybody else (organizers included) neither. No other response embeds a `UserResponse` (team members, captain, join requests, player profiles, lineups, matches, referees, history and sanctions are built from explicit `id`/`fullName` fields), so the phone never reaches other players, anonymous visitors or the public history; `PhoneNumberExposureTest` scans every record and fails when a new DTO declares a phone or embeds a `UserResponse`. The tournament player export (`GET /tournaments/{id}/players/export`, documents, birth dates and phones included) is ADMIN only and audited. `GET /players` and `GET /players/{id}/profile` require CAPTAIN or ORGANIZER.
- Podium photos (`FileOwner.Category.PODIUM`) are public images: anonymous visitors read them through `GET /tournaments/{id}/history/podium/{position}/photo`, which serves only the file currently attached to that position (`FileAccessGuard` lets any authenticated user read them through `/files/{id}` too).
- Files: uploads are validated by magic bytes and stored with `kind` + owner metadata in GridFS; `GET /files/{id}` serves receipts only to ORGANIZER/ADMIN or the registering captain (`FileAccessPolicy` port implemented in `tournaments/infrastructure`), adds `X-Content-Type-Options: nosniff` and sends PDFs as attachments. Replaced/orphaned binaries are deleted after the DB transaction commits (`FileDeletionScheduler`), and a binary stored by a transaction that then rolls back (receipt, rulebook, venue image, photo) is deleted by `GridFsFileStorage` in an `afterCompletion(STATUS_ROLLED_BACK)` synchronization.

### 3.3 Data model (PostgreSQL, Flyway `V1__schema.sql` + `V3__match_walkover.sql` + `V4__join_request_direction.sql` + `V5__pending_join_request_uniqueness.sql` + `V6__tournament_suspension.sql` + `V7__tournament_groups.sql` + `V8__bracket_links_and_history.sql` + `V9__user_phone_number.sql`)

All ids are `BIGSERIAL`. Timestamps are `TIMESTAMPTZ`. Enums are `VARCHAR` with CHECK constraints
(mapped as `@Enumerated(EnumType.STRING)`).

```
users
  id, full_name, email UNIQUE, password_hash,
  school_relation  STUDENT|PROFESSOR|ADMINISTRATIVE|GRADUATE|FAMILY,
  academic_program SYSTEMS_ENGINEERING|AI_ENGINEERING|CYBERSECURITY_ENGINEERING|STATISTICS_ENGINEERING|OTHER,
  semester INT NULL, status ACTIVE|INACTIVE, birth_date DATE,
  document_type CC|TI|CE|PASSPORT, document_number, created_at, updated_at,
  phone_number VARCHAR(20) NULL   (V9; normalised "+57XXXXXXXXXX" or "+" and 8-15 digits,
                                   CHECK ck_users_phone_number '^\+[1-9][0-9]{7,14}$'; NULL = none)
  UNIQUE(document_type, document_number)

user_roles (user_id FK, role) PK(user_id, role)

audit_logs
  id, actor_user_id NULL FK, action VARCHAR, entity_type, entity_id, details JSONB NULL, created_at

player_profiles
  user_id PK FK, position GOALKEEPER|DEFENDER|MIDFIELDER|FORWARD, jersey_number INT (1..99),
  photo_file_id VARCHAR NULL, created_at, updated_at

teams
  id, name UNIQUE, colors VARCHAR, captain_user_id FK, status ACTIVE|INACTIVE, created_at, updated_at

team_members (team_id FK, user_id FK, joined_at) PK(team_id, user_id)
  -- "a player cannot be in two teams": service enforces user_id appears in at most one ACTIVE team.
  -- captain is also a member.

join_requests
  id, team_id FK, player_user_id FK, status PENDING|ACCEPTED|REJECTED|CANCELLED, message NULL,
  direction REQUEST|INVITATION (default REQUEST), created_at, resolved_at NULL, version
  -- REQUEST: the player asks the team. INVITATION: the team (captain) invites the player.
  -- service checks, and V5 partial unique indexes guarantee against double submits:
  --   ux_join_requests_pending_request    UNIQUE(player_user_id)          WHERE status='PENDING' AND direction='REQUEST'
  --   ux_join_requests_pending_invitation UNIQUE(team_id, player_user_id) WHERE status='PENDING' AND direction='INVITATION'
  -- i.e. at most ONE PENDING REQUEST per player (invitations do not count) and at most ONE
  -- PENDING INVITATION per (team, player). A violation (lost race) is answered with the same 409
  -- message as the service check (services flush the insert and translate the constraint;
  -- GlobalExceptionHandler maps the constraint names as a fallback). V5 first cancels any
  -- pre-existing duplicate PENDING rows, keeping the newest.

tournaments
  id, name, start_date DATE, end_date DATE, registration_deadline DATE, max_teams INT, fee NUMERIC(12,2),
  status DRAFT|ACTIVE|IN_PROGRESS|FINISHED|SUSPENDED, suspended_from_status DRAFT|ACTIVE|IN_PROGRESS NULL
  (set exactly while SUSPENDED), rulebook_file_id NULL, created_by FK, created_at, updated_at

venues
  id, tournament_id FK, name, description, image_file_id NULL

registrations
  id, tournament_id FK, team_id FK, receipt_file_id, status UNDER_REVIEW|APPROVED|REJECTED|CANCELLED,
  review_note NULL, reviewed_by NULL FK, created_at, reviewed_at NULL
  UNIQUE(tournament_id, team_id)

tournament_groups                                  (V7, competition module)
  id, tournament_id FK ON DELETE CASCADE, name ("Grupo A".."Grupo Z"), position INT (0-based, A = 0), created_at
  UNIQUE(tournament_id, name), UNIQUE(tournament_id, position), UNIQUE(id, tournament_id)

tournament_group_teams                             (V7) a team in a group
  id, group_id, tournament_id, team_id FK, registration_id, assigned_at
  FK (group_id, tournament_id) -> tournament_groups(id, tournament_id) ON DELETE CASCADE
  FK (registration_id, tournament_id, team_id) -> registrations(id, tournament_id, team_id) ON DELETE CASCADE
  UNIQUE(tournament_id, team_id)          -- one group per team and tournament
  -- Hangs off the APPROVED registration instead of being a column of registrations: registrations
  -- belong to the tournaments module, which must not know competition (one-way dependencies), and
  -- removing a team before the start (registration row deleted) drops its assignment by cascade.

matches
  id, tournament_id FK, phase GROUP|QUARTERFINAL|SEMIFINAL|FINAL, round_number INT,
  group_id NULL (V7; FK (group_id, tournament_id) -> tournament_groups ON DELETE CASCADE,
                 CHECK group_id IS NULL OR phase = 'GROUP'; NULL for knockout and legacy group matches),
  home_team_id FK, away_team_id FK, venue_id NULL FK, referee_user_id NULL FK,
  scheduled_at TIMESTAMPTZ NULL, status SCHEDULED|PLAYED|CANCELLED,
  home_score INT NULL, away_score INT NULL, home_penalties INT NULL, away_penalties INT NULL,
  cancel_reason DISQUALIFIED|NO_SHOW NULL, created_at, updated_at,
  next_match_id NULL FK matches ON DELETE SET NULL, next_slot HOME|AWAY NULL   (V8 feeder link)
    UNIQUE(next_match_id, next_slot) WHERE next_match_id IS NOT NULL
    -- set on every match of a knockout round when the next round is drawn from its winners: the
    -- match it feeds and the side its winner takes there. NULL for group matches, the final, a
    -- round not drawn yet, and knockout rounds drawn before V8.
  legacy_fold BOOLEAN NOT NULL DEFAULT FALSE   (V8) TRUE on every knockout match that existed when
    V8 ran (bracket seeded and folded under the old rule) and on rounds drawn from such matches.

match_events
  id, match_id FK, team_id FK, player_user_id FK, type GOAL|YELLOW_CARD|RED_CARD, minute INT NULL

lineups
  id, match_id FK, team_id FK, formation F_3_2_1|F_2_3_1|F_4_1_1|F_1_3_2 (default F_2_3_1), updated_at
  UNIQUE(match_id, team_id)

lineup_players (lineup_id FK, player_user_id FK, starter BOOLEAN) PK(lineup_id, player_user_id)

tournament_sanctioned_teams                        (V8, competition module) snapshot of a sanctioned team
  id, tournament_id FK ON DELETE CASCADE, team_id NULL FK teams ON DELETE SET NULL, team_name, note NULL (500),
  created_by NULL FK users ON DELETE SET NULL, created_at
  UNIQUE(tournament_id, team_id)               -- once per tournament and team (NULL ids of deleted teams never collide)

tournament_sanctioned_team_members                 (V8) the members copied at the moment of the sanction
  id, sanction_id FK ON DELETE CASCADE, user_id NULL FK users ON DELETE SET NULL, full_name, captain BOOLEAN

tournament_podium_photos                           (V8) optional extra photo per podium position
  id, tournament_id FK ON DELETE CASCADE, position 1..3, team_id NULL FK teams ON DELETE SET NULL,
  file_id (GridFS, kind IMAGE, owner PODIUM), uploaded_by NULL FK users, uploaded_at
  UNIQUE(tournament_id, position)
```

### 3.4 Business rules (where enforced)

**identity**
- Contact phone (`phoneNumber`, optional, `identity/domain/PhoneNumbers`, Bean Validation `@PhoneNumber` on `RegisterRequest`/`UpdateUserRequest`, mirrored by `frontend/src/lib/phone.ts`): a Colombian mobile (10 digits starting with 3, optionally preceded by `+57`/`57`) or an international number (`+`, country code not starting with 0, 8–15 digits; a `+57` number must be a mobile). Spaces, dashes, dots and parentheses are accepted and dropped; at most 30 input characters. Stored normalised: `+57XXXXXXXXXX` or `+digits`. Blank = no phone. Invalid → 400 "Ingrese un celular válido: 10 dígitos que empiecen por 3 o un número internacional con +código de país." (also re-checked in the use case, `UserService.normalizePhone`). Asked (optionally) at registration; editable by the user or an ADMIN through `PATCH /users/{id}` at any time, never frozen by tournaments.
- Register: the form no longer asks for the school relation (nor semester) or the role: `schoolRelation` is optional (null until an ADMIN sets it via `PATCH /users/{id}`) and `initialRole` defaults to PLAYER (GUEST still accepted from the API); `semester` required iff STUDENT; any e-mail; PLAYER only within the age range (409 "Para ser jugador la edad debe estar entre 5 y 100 años.", built from the configured bounds); status ACTIVE. Audit `USER_REGISTERED`.
- Login → JWT. Audit `LOGIN`. Logout audit `LOGOUT`.
- Admin assigns/removes/lists any role (except cannot remove own ADMIN). Assigning PLAYER re-checks the age range; CAPTAIN requires PLAYER and cannot be removed by hand while the user captains an ACTIVE team; likewise PLAYER cannot be removed while the user captains or belongs to an ACTIVE team (409). PLAYER and CAPTAIN cannot be removed while the user is locked by a tournament (member of a locked team, 409).
- CAPTAIN is not appointed by anyone: `RoleService.grantCaptainForNewTeam(userId, teamId)` grants it to whoever creates a team (same transaction, audit `ROLE_ASSIGNED` `{role: CAPTAIN, reason: TEAM_CREATED, teamId}`, actor = creator) and `RoleService.revokeCaptainForClosedTeam(actorId, userId, teamId)` removes it when the team is inactivated (audit `ROLE_REMOVED`, reason `TEAM_INACTIVATED`, actor = who inactivated). Dependency is one-way: `teams` calls `identity`, never the reverse.
- Organizer creates `REFEREE` users (no domain or age rule); organizer never changes roles.
- Inactivate user (admin): forbidden if user is a member of a team with an APPROVED registration in a tournament with status ACTIVE or IN_PROGRESS. Audit `USER_INACTIVATED`.

**players**
- Update basic info (self or admin): fullName, academicProgram, semester (semester only for STUDENT), phoneNumber (absent/`null` keeps it, blank removes it, otherwise validated and normalised; editable while locked by a tournament; the audit records `phoneNumber: ADDED|CHANGED|REMOVED`, never the number). `schoolRelation` only by an ADMIN: a non-admin sending a different value gets 403 "Solo un administrador puede cambiar la relación con la Escuela.". While the user is locked by a tournament nobody, ADMIN included, may change `schoolRelation` or `academicProgram` (409; they decide team eligibility); name and semester stay editable. Email/password immutable. Audit `USER_UPDATED`.
- Sport profile create/update (PLAYER): position, jerseyNumber, photo. Creation re-checks the player age range (legacy accounts). Never deletable. Audit `PROFILE_CREATED|PROFILE_UPDATED`. Editing while in a team (product decision 2026-10, replacing "update forbidden while member of an ACTIVE team"):
  - Photo: always editable (upload `POST /players/me/profile/photo`, remove `DELETE /players/me/profile/photo`), in a team or not, during a tournament too. The replaced/removed binary is deleted after commit. Removing a photo that does not exist → 409 "El jugador no tiene foto de perfil.".
  - Position: always editable. Basic info (`PATCH /users/{id}`: name, semester, program) was already editable in a team; the ADMIN-only school relation and the freeze of relation/program during tournaments are unchanged.
  - Jersey number: editable in a team except while the team is locked by a tournament (APPROVED registration in an ACTIVE, IN_PROGRESS or SUSPENDED one, `TeamGateway.isLocked`): 409 "No puede cambiar su dorsal mientras su equipo juega un torneo.". Inside a team it must stay unique: 409 "El número de camiseta N ya está en uso en el equipo 'X'." (same message as joining). A jersey change locks the team row first (`TeamGateway.lockMemberIds`, `SELECT ... FOR UPDATE`), so it serialises with teammates' changes and with the registration approval that would lock the team. Sending the unchanged number (e.g. with a new position) is never refused. `PlayerProfileResponse.teamLocked` tells the UI to disable the jersey field.
  - ADMIN moderation: `DELETE /admin/users/{id}/photo` (ADMIN only, any state) removes any player's photo, deletes the binary after commit and audits `PLAYER_PHOTO_REMOVED_BY_ADMIN` `{reason: "moderación", photoFileId}`; the player may upload a new one. Teams have no photo of their own: the photos in a roster are the members' profile photos.
- Join request (`direction = REQUEST`; a user who literally holds PLAYER, with profile, not in a team — ADMIN implies PLAYER for endpoint security but not here, same rule as founding a team: 403 "Solo un usuario con el rol de jugador puede solicitar unirse a un equipo."; the team page shows "Solicitar unirme" only to `hasExactRole('PLAYER')`): at most one PENDING request at a time; team must be ACTIVE, not locked (roster frozen, 409) and have < 13 members (`TeamEligibility.MAX_MEMBERS`). Player may cancel while PENDING.
- Captain accepts/rejects. Accept → locks the player row, locks the team row, validates jersey uniqueness within team, team size < 13, player not in another team → inserts membership, marks every other PENDING request **and invitation** of that player CANCELLED. Audit `JOIN_REQUEST_ACCEPTED|REJECTED`. The `/join-requests/{id}/*` actions refuse INVITATION rows (409).
- Invitation (`direction = INVITATION`, `players/application/InvitationService`): sent by the team captain (or ADMIN); team ACTIVE, not locked (roster frozen, 409), with < 13 members; invited user ACTIVE, PLAYER, with a sport profile, not in an ACTIVE team; no other PENDING invitation from the same team to the same player (409). A player may hold pending invitations from several teams. Only the invited player accepts/rejects (403 otherwise); accepting runs exactly the request-acceptance membership step (`JoinRequestService.completeMembership`). Captain or ADMIN may cancel while PENDING. Inactivating a team cancels its pending requests and invitations, and so does approving its tournament registration (`TeamService.closeRecruitment`, audit reason `ROSTER_FROZEN`), because a frozen roster could never accept them. Audit `INVITATION_SENT|ACCEPTED|REJECTED|CANCELLED`.
- Free-agent search: `GET /api/players?position=&available=true` returns players with a profile and no active team.

**teams**
- Create (any user who literally holds PLAYER; the ADMIN role hierarchy does not count, 403 otherwise): name unique, colors; the creator must not be in another ACTIVE team. The creator becomes first member (needs a sport profile; if none, 409 with clear message) and, in the same transaction, captain (CAPTAIN role granted if absent). Audit `TEAM_CREATED` (+ `ROLE_ASSIGNED`).
- Update name/colors: forbidden if team has an APPROVED registration in an ACTIVE or IN_PROGRESS tournament. Audit `TEAM_UPDATED`.
- Add member (`TeamService.addMember`, reached by request/invitation acceptance): refused while the team is locked, so its roster is frozen for the whole tournament.
- Remove member: same lock; captain cannot remove themself. Audit `TEAM_MEMBER_REMOVED`.
- Leave (`POST /teams/{id}/leave`, any member): 403 for a non-member; 409 for the captain ("El capitán no puede salir del equipo. Si necesita retirarse, un administrador puede eliminar el equipo."); 409 while the team is locked (same `TeamLockPort` rule as removing a member). Removes only the membership (the profile/session team is derived from it; a member holds no pending request or invitation). Every roster change (add, remove, leave) and the registration approval lock the team row (`TeamService.requireTeamForUpdate`), so a departure cannot slip under the minimum between the approval's re-check and its commit. Audit `TEAM_MEMBER_LEFT` `{userId, remainingMembers}`.
- Inactivate team (captain/admin): same lock. Cancels pending requests/invitations of the team, then revokes CAPTAIN from its captain. Audit `TEAM_INACTIVATED` (+ `ROLE_REMOVED`).
- Delete (`DELETE /teams/{id}`, ADMIN only): 409 while the team appears in any match or holds an UNDER_REVIEW/APPROVED registration (delete — or suspend, then delete — that tournament first; `TeamMatchPort` / `TeamRegistrationPort`). Otherwise deletes the team, its memberships and join requests/invitations (FK cascade) and its REJECTED/CANCELLED registrations (receipts deleted after commit); players stay as users. The former captain loses CAPTAIN unless they still captain an ACTIVE team. Audit `TEAM_DELETED` (+ `ROLE_REMOVED` reason `TEAM_DELETED`).
- Eligibility check (used at registration and re-checked at approval under the team row lock): 8 ≤ members ≤ 13 (`TeamEligibility.MIN_MEMBERS`/`MAX_MEMBERS`, product decision 2026-10 replacing the spec's 7..12; the maximum also blocks every new membership, so a team never grows past 13, while the minimum only gates registration); no duplicate jersey numbers; strictly more than half of members with `academic_program != OTHER`; every member has a sport profile.

**tournaments**
- Create (ORGANIZER): status DRAFT. Update fields only in DRAFT. Delete only in DRAFT. Audit `TOURNAMENT_*`.
- Activate: DRAFT → ACTIVE (requires rulebook uploaded and ≥ 1 venue). Start: ACTIVE → IN_PROGRESS from the start date until the end date (`start_date <= today <= end_date`); requires ≥ 2 APPROVED registrations. Finish: IN_PROGRESS → FINISHED when the end date has been reached (`today >= end_date`, as implemented in `TournamentService.finish`) **or** the FINAL has decided the champion: PLAYED, or CANCELLED with a walkover winner (`FinalMatchAdapter`). Refused while SCHEDULED matches remain. Finish locks the tournament row like advance and knockout creation.
- Suspend / resume (ADMIN or ORGANIZER, row locked): DRAFT, ACTIVE or IN_PROGRESS → SUSPENDED, remembering `suspended_from_status`; resume returns exactly there. FINISHED cannot be suspended. A SUSPENDED tournament is hidden from `GET /tournaments` and `GET /tournaments/{id}` (404) for everybody but ADMIN/ORGANIZER, is never "current", and refuses every change to itself, its registrations, matches, results and lineups (409 "El torneo «X» está suspendido: no se puede … hasta que un organizador o un administrador lo reanude."). Its approved teams stay locked. Audit `TOURNAMENT_SUSPENDED|RESUMED`.
- Hard delete (`POST /tournaments/{id}/hard-delete` `{confirmName}`, ADMIN only, any status, row locked): `confirmName` must equal the name (400). Venues, registrations and matches (with events and lineups) go through the V1 `ON DELETE CASCADE` foreign keys; groups, sanctioned teams (with their members) and podium photos go through the V7/V8 cascades; rulebook, venue images, receipts and podium photos (collected through the `TournamentFilesPort`, answered by competition's `TournamentPodiumFilesAdapter`, so tournaments never depends on competition) are deleted after commit. Teams stay (and are no longer locked). Audit rows are kept; audit `TOURNAMENT_DELETED` with `hardDelete: true`.
- Rulebook: `POST /api/tournaments/{id}/rulebook` multipart PDF (DRAFT/ACTIVE). Venues: CRUD with image, only while not FINISHED (and not SUSPENDED). A venue's image is replaced (`PUT /tournaments/{id}/venues/{venueId}/image`) or removed (`DELETE .../image`) by ORGANIZER/ADMIN in DRAFT, ACTIVE and IN_PROGRESS, whether or not it already has one (tournament row locked; 409 while SUSPENDED or FINISHED; the old binary is deleted after commit). Audit `VENUE_UPDATED`.
- Player export `GET /tournaments/{id}/players/export` (ADMIN only, any status, `PlayerExportService` + `PlayerSpreadsheetWriter` port, Apache POI adapter `XlsxPlayerSpreadsheetWriter`): `.xlsx` named `jugadores-<slug>.xlsx` (accents stripped, lower case, non-alphanumerics → "-"; `torneo-<id>` when empty), `Cache-Control: no-store`. Sheet "Aprobados": one row per member of every APPROVED team; sheet "En revisión": the same for UNDER_REVIEW teams (a snapshot, those rosters are not frozen; empty once the tournament started). Rejected/cancelled are left out. Columns: Nombre, Correo, Celular (text cell, normalised `+57…`, empty when none), Tipo documento, Número documento, Fecha de nacimiento (real date, dd/mm/yyyy), Edad (full years, app clock), Relación con la Escuela, Programa académico, Semestre (students only), Equipo, Capitán (Sí/No), Posición, Dorsal, Estado inscripción; Spanish labels from the enums' `label()`. Bold frozen header row; column widths computed from the content (POI's `autoSizeColumn` needs AWT fonts a headless server may lack). Ordered by team, captain first, then name. Rosters come from `TeamService.rosterOf` (read model `TeamRosterEntry`). Audit `PLAYERS_EXPORTED` `{approvedPlayers, underReviewPlayers}`.
- Registration (CAPTAIN of the team): tournament ACTIVE, `today <= registration_deadline`, approved count < max_teams, team passes eligibility, no existing non-cancelled/non-rejected registration. Receipt file required (image or PDF). Status UNDER_REVIEW. Audit `REGISTRATION_CREATED`.
- Organizer approve/reject (only from UNDER_REVIEW; approve also re-checks capacity). Captain cancel only from UNDER_REVIEW. Audit.
- Team members of an APPROVED registration in ACTIVE/IN_PROGRESS tournaments are "locked" (see teams/identity).

**competition**
- Format: **groups chosen by the organizer** (any sizes) whose matches are generated for every group at once (**hybrid fixture**, "Generar partidos": a round robin inside each group, never across groups) or created **by hand**, then edited, deleted and re-created freely → **knockout built by the organizer** from an editable suggestion (2, 4 or 8 teams). The old whole-tournament fixture (`POST /matches/generate`, every team against every team) stays removed: with many teams it meant 100+ matches.
- Groups (`GroupStageService`, ORGANIZER; ADMIN inherits): created while the tournament is ACTIVE or IN_PROGRESS and no knockout match exists, named by the first free letter ("Grupo A", "Grupo B", …, at most 26). Only APPROVED teams are assigned, at most one group per team. A team is moved (re-assigned) or removed only while it has no match in its group (409 "No se puede mover/quitar a 'X' del Grupo A: ya tiene partidos en ese grupo."). A group is deleted only with no team and no match. Audit `GROUP_CREATED|GROUP_DELETED|GROUP_TEAM_ASSIGNED|GROUP_TEAM_REMOVED`.
- Group match `POST /tournaments/{id}/matches` (IN_PROGRESS, not suspended, knockout not started): group, home, away, `scheduledAt` (years 2000–2100, else 400), optional venue of the tournament and ACTIVE referee. Both teams must belong to the group, differ, and not already meet in another match of the group in either orientation, whatever its status (409 naming the existing match: modify or reopen it instead). `round_number` = next matchday of the busier team inside the group. Audit `MATCH_CREATED`.
- "Todos contra todos" per group `POST /tournaments/{id}/groups/{groupId}/round-robin`: creates only the pairings the group is missing, drawn by `RoundRobinFixtureStrategy`, one matchday per day at 18:00 (from the start date or the day after the group's latest kick-off, never in the past), venues and referees cycled, skipping any venue or referee already busy at that same kick-off in a non-cancelled match of the tournament (left empty for the organizer when none is free, never double-booked). A generated kick-off after 2100 is refused with 409 "No hay fechas disponibles antes del año 2101 para programar los partidos." and nothing is created (same rule for the knockout and `advance`). 409 with fewer than 2 teams or when nothing is missing. Audit `MATCHES_GENERATED` with `groupId`.
- "Generar partidos" `POST /tournaments/{id}/groups/round-robin` (ORGANIZER, `GroupStageService.generateAll`, tournament row locked): the per-group round robin above in **every** group with ≥ 2 teams, in one transaction (same scheduling: 18:00, one matchday per day, busy venues/referees skipped, nothing created when a kick-off would fall after 2100). Preconditions, 409 each: IN_PROGRESS (same rule as a manual group match), not SUSPENDED, knockout not started, at least one group with 2 teams ("Para generar los partidos se necesita al menos un grupo con 2 equipos."), and **every APPROVED team assigned to a group** ("Asigne todos los equipos aprobados a un grupo antes de generar los partidos. Falta(n): 'X', 'Y'."). Idempotent: only missing pairings are created; when nothing is missing the answer is 200 with `created: 0` and "Todos los grupos ya tienen un partido para cada pareja de equipos: no se creó ningún partido." (no audit). Response `{created, groups[{groupId,name,teams,created}], matches, message}`. Audit `MATCHES_GENERATED` `{allGroups: true, matches, groups}`. The UI disables the button with the same reasons.
- Delete a group match `DELETE /tournaments/{id}/matches/{matchId}` (ORGANIZER, `GroupStageService.deleteMatch`, row locked): only a GROUP match without result (SCHEDULED or CANCELLED; PLAYED → 409 "… ya tiene un resultado: reábralo antes de eliminarlo."), same preconditions as creating one (IN_PROGRESS, not suspended, knockout not started); a knockout match → 409 (undo the phase instead). Lineups go with it (FK cascade). The pairing can then be created again by hand (or by "Generar partidos"). Audit `MATCH_DELETED` `{groupId, status, homeTeamId, awayTeamId, scheduledAt}`. Not to be confused with `DELETE /matches/{id}?reason=` (soft cancel).
- Legacy tournaments: GROUP matches with `group_id` NULL (created by the old generator) keep working unchanged and count only for the general table (one implicit group). In a match with a group, replacing a team (`PATCH /matches/{id}`) follows the group rules above instead of the round-slot rule.
- Knockout suggestion `GET /tournaments/{id}/knockout/suggestion?size=2|4|8` (ORGANIZER, read-only; `KnockoutSeeding`, pure domain): with groups, winners first, then runners-up, then thirds…; when a whole level does not fit, the best of that level by points, GD, GF, name. Seeds are paired top half against bottom half choosing, among all assignments, the one with the fewest same-group pairings, then (quarter-finals) the fewest same-group teams that could meet in the semi-final, then the closest to 1vN: two groups give 1°A–2°B, 1°B–2°A. The pairings come in **standard bracket order** so the top seeds meet as late as possible: 8 → [1v8, 4v5, 3v6, 2v7], 4 → [1v4, 2v3], 2 → [1v2] (seeds 1 and 2 in opposite halves; with groups the two best group winners, e.g. 1°A and 1°B, too). Not enough teams → 409. Size other than 2/4/8 → 400.
- Knockout creation `POST /tournaments/{id}/knockout` `{pairings:[{homeTeamId,awayTeamId}]}` (ORGANIZER): 1, 2 or 4 pairings (FINAL / SEMIFINAL / QUARTERFINAL), distinct teams (400), all APPROVED (409), IN_PROGRESS and not suspended, no knockout match yet (409, undo it first), no SCHEDULED group match left (409). Matches are created in the given order (bracket order), first matchday after the latest kick-off. Audit `KNOCKOUT_CREATED`. Undoing that phase goes back to the group stage.
- Later knockout rounds: when the last match of a knockout phase gets its outcome (result or walkover) and the next phase does not exist, the next phase is drawn **automatically** from the winners in **bracket order** (match id, never kick-off order) by `KnockoutFixtureStrategy`, which pairs **adjacent** matches: winner of match 1 vs winner of match 2, 3 vs 4 (W1–W2 and W3–W4 in the semi-finals; W1–W2 in the final). Every match of the finished round gets its **feeder link** (`next_match_id`, `next_slot`: match i feeds new match i/2, HOME when i is even). Backward compatibility: a round whose matches are all `legacy_fold` (knockouts that existed when V8 ran) is still **folded** (W1–W4, W2–W3, `KnockoutFixtureStrategy.fold`), linked accordingly, and the new round inherits the mark; undo-phase clears the links pointing at the deleted round. Until 2026-10 every round was folded and the first round listed by seed (1v8, 2v7, 3v6, 4v5). Audit `MATCHES_GENERATED` with `automatic: true`. When no day is left before 2101 the result is still recorded and the round is simply not drawn; `advance` then answers the 409 above until a kick-off is moved earlier. `POST /tournaments/{id}/matches/advance` (ORGANIZER) does the same by hand (e.g. after undoing a round) and never leaves the group stage (409 pointing to "Armar eliminatoria"). Knockout winners: score, then penalties (a draw without a decisive shoot-out is refused with **409**). The knockout is built **once**: correcting a group result afterwards does not change existing knockout matches; if the qualifiers change, undo the knockout phase(s) and build it again (the frontend warns about it).
- **Correction power (product decision, overrides the PDF rule "only future matches, only date/time/venue/referee")**: while the tournament is IN_PROGRESS the ORGANIZER (ADMIN inherits) can fix any match, whatever its status, so every system or human error is recoverable. Outside IN_PROGRESS nothing is editable (FINISHED is read-only). Match state machine: SCHEDULED → PLAYED | CANCELLED, and PLAYED | CANCELLED → SCHEDULED (reopen).
- Update match `PATCH /matches/{id}` (ORGANIZER, IN_PROGRESS, any status): `scheduledAt` (may be in the past, to record what actually happened), `venueId` (a venue of the tournament), `refereeId` (an ACTIVE referee), `homeTeamId`/`awayTeamId` (null = unchanged). Teams only while the match is not PLAYED (409 "Reabra el partido antes de cambiar los equipos: tiene un resultado registrado."); both teams APPROVED in the tournament, distinct, and a team entering the match must not already play another non-CANCELLED match of the same GROUP round or the same knockout phase (409 naming the team). In a knockout phase the walkover winner of a CANCELLED match also occupies its slot (it cannot be entered into another match of that phase). The walkover winner of a cancelled match cannot be replaced without reopening it. A team leaving a knockout match while it already stands in the NEXT knockout phase (it won this match before it was reopened, or went through by walkover there) is refused with 409 "Deshaga la fase {siguiente} antes de cambiar los equipos de este partido: {equipo} ya figura en ella." The lineup of a team that leaves the match is deleted. Audit `MATCH_UPDATED` (team changes record `previousHomeTeamId/homeTeamId/previousAwayTeamId/awayTeamId`).
- Delete match: only with `cancel_reason` DISQUALIFIED|NO_SHOW → status CANCELLED (soft); a knockout match also needs `winnerTeamId` (walkover). Audit `MATCH_CANCELLED`.
- Result `POST /matches/{id}/result` (ORGANIZER, IN_PROGRESS, match SCHEDULED or PLAYED, regardless of later phases): home/away score, optional penalties (a knockout draw needs a decisive shoot-out), events list (goals must reference a member of the scoring team; goals count per team must equal the score). SCHEDULED → PLAYED, audit `MATCH_RESULT_RECORDED`; PLAYED → score, penalties and events replaced **without reopening** (the frontend's "Corregir resultado", prefilled), audit `MATCH_RESULT_CORRECTED`.
- Knockout propagation (result, correction, or walkover of a knockout match): nothing changes when the winner already stands in the NEXT knockout phase. Otherwise the target is the match the source **feeds through its link** (`next_match_id`; a slot the organizer filled by hand with a third team is left alone); a legacy match without link falls back to the next-phase match holding the loser, which is exactly the slot the old fold had sent it to. If the target holds the loser and is not PLAYED, the loser is replaced by the winner on the same side and the loser's lineup for it is deleted (audit `MATCH_UPDATED` with `reason: RESULT_CORRECTION`, `sourceMatchId`, `previousTeamId`, `teamId`). If it is PLAYED (or CANCELLED sending the loser through by walkover) the whole request is refused with 409 "El partido de {fase} ya se jugó con {equipo}; reábralo antes de corregir este resultado." It is also refused when the loser already stands in any LATER phase (it won the next match before that one was reopened): 409 "El equipo {equipo} ya avanzó a la {fase}: deshaga o corrija primero esa fase." The check runs before anything is written, and the use case is one transaction.
- Reopen `POST /matches/{id}/reopen` (ORGANIZER, IN_PROGRESS): PLAYED or CANCELLED → SCHEDULED, clearing score, penalties, events, cancel reason and walkover winner (the match is "as if never played": it leaves the tables, its teams can be replaced again, and a result can be recorded anew). It does not touch the next knockout round (a reopened knockout match whose winner already went further can only get the same winner back: any other outcome is refused by the propagation rule above). A GROUP match is not reopened once any knockout match exists (409 "La fase de grupos ya terminó: deshaga la eliminatoria antes de reabrir partidos de grupo."; correcting its result stays allowed). Audit `MATCH_REOPENED`.
- Undo phase `POST /tournaments/{id}/matches/undo-phase` (ORGANIZER, IN_PROGRESS): deletes every match of the tournament's latest phase when none of them is PLAYED (409 "No se puede deshacer la fase {fase}: ya tiene partidos jugados. Reábralos primero."); events and lineups go with them (`ON DELETE CASCADE`). Undoing the first knockout round goes back to the group stage (groups editable again); undoing the group stage deletes its unplayed matches. Audit `PHASE_UNDONE` on entity `TOURNAMENT`.
- `MatchResponse` flags: `resultEditable` = IN_PROGRESS && status ∈ {SCHEDULED, PLAYED}; `teamsEditable` = IN_PROGRESS && status ≠ PLAYED; `reopenable` = IN_PROGRESS && status ∈ {PLAYED, CANCELLED}.
- Finish stays refused while SCHEDULED matches remain; standings are recomputed on every read, so corrections show up immediately in the tables (but not in an already built knockout, see above).
- Lineup (CAPTAIN of a team playing the match, before `scheduled_at`): formation (default F_2_3_1) + exactly 7 starters, all team members. Visible to members of that team, organizer, admin, referee of the match.
- Standings per tournament (computed on read from PLAYED GROUP matches): P, W, D, L, GF, GA, GD, Pts (3/1/0). Order: Pts, GD, GF, name. The **general table** is unchanged (every approved team, every PLAYED group match with or without a group). **Group tables** (`GET /standings/groups`): one per group with its teams and only the PLAYED matches of that group, same order; teams may have played a different number of matches.
- Visibility: every public competition read of a SUSPENDED tournament (matches, match detail, standings, group standings, bracket, stats, team results, groups, venues) answers 404 to anybody but ADMIN/ORGANIZER (`TournamentService.requireVisible`, `MatchService.requireVisibleMatch` for a single match, its lineups and its sanctioned players), like the tournament detail; `GET /referees/me/matches` leaves those matches out. `GET /tournaments/{id}/registrations/mine` stays available to the team's captain (own data).
- Stats: top scorers (GOAL events grouped by player), match history (PLAYED matches ordered by date), results per team.
- Referee: `GET /api/referees/me/matches`; `GET /api/matches/{id}/sanctioned-players` — a player is sanctioned for a match if in their team's previous PLAYED match they got a RED_CARD, or if the yellow cards of that previous match made their accumulated YELLOW_CARD count in the tournament cross an even threshold (`before / 2 < after / 2`: 1→2, 1→3 and 3→4 suspend, 2→3 does not).
- Bracket view: matches grouped by phase with team names/scores. Knockout columns are drawn in pairs: the two matches that feed the same next-round match sit together next to it, joined by a connector (order from the feeder links; legacy rounds without links are matched by the team that went through, `orderKnockoutPhases`).

**history** (FINISHED tournaments; `competition/application/TournamentHistoryService`, rules in the pure `competition/domain/TournamentRecords`, recomputed from the matches on every read; it lives in competition because every number comes from the matches)
- Public (anonymous too): `GET /tournaments/history` (FINISHED, latest end date first, with champion) and `GET /tournaments/{id}/history` (409 unless FINISHED; a SUSPENDED one is 404 like every read).
- **Campeón** / **Subcampeón**: the winner and the loser of the FINAL that decided a champion (PLAYED, penalties included, or CANCELLED with a walkover winner). **3er puesto** (there is no third-place match): the better of the two semi-final losers by their numbers over **all** their PLAYED matches of the tournament (points 3/1/0, then goal difference, then goals for, then name); a level knockout match decided on penalties counts as a draw. The compared candidates are returned (`thirdPlaceCandidates`). Without a decided final (e.g. finished by date during the group stage) the podium is the top 3 of the general table with at least one match played; only the positions that exist are returned (`podiumSource` KNOCKOUT | STANDINGS | null).
- **Valla menos vencida**: fewest goals conceded per match played (all phases, PLAYED matches with a score; exact comparison by cross-multiplication); ties: more matches, fewer total conceded, name. Shows goals conceded and matches. **Goleador(es)**: the highest goal count, every player tied on it, with team and goals.
- **Equipos sancionados** (ADMIN/ORGANIZER, FINISHED only, tournament row locked; `POST /tournaments/{id}/history/sanctions {teamId, note?≤500}`, `DELETE .../sanctions/{sanctionId}`): the team must have taken part (APPROVED registration or a match; 409 otherwise), once per tournament (409 "El equipo 'X' ya figura como sancionado en este torneo.", also on the unique-key race). Stored as a **snapshot**: team name and current members (name, captain flag) are copied; renaming or deleting the team keeps the entry (`team_id` / `user_id` SET NULL, the UI shows "(equipo eliminado)"). Audit `TOURNAMENT_TEAM_SANCTIONED` / `TOURNAMENT_SANCTION_REMOVED`.
- **Podium photos** (optional, ADMIN/ORGANIZER, FINISHED only, row locked): `PUT /tournaments/{id}/history/podium/{position}/photo` multipart "file" (position 1..3, else 400; 409 when nobody holds that position), validated by magic bytes like every image (`FileKind.IMAGE`, owner `PODIUM`); it replaces the previous one, whose binary is deleted after commit. `DELETE` removes it (409 without photo; binary deleted after commit). Public read `GET .../podium/{position}/photo` (`nosniff`, inline, `Cache-Control: public, max-age=300`). Removed by the tournament hard delete. Audit `PODIUM_PHOTO_UPDATED` / `PODIUM_PHOTO_REMOVED`.

### 3.5 REST API (prefix `/api`)

Errors: `{ "timestamp", "status", "error", "message", "path", "traceId", "details": [ {field, message} ]? }`.
`traceId` (12 hex characters) is generated per request by `shared/config/TraceIdFilter` (runs before
the security chain), echoed in the `X-Trace-Id` response header (exposed through CORS), put in the
SLF4J MDC and printed on every log line of the request (`logging.pattern.level`). Every error body
carries it, including the 401/403 written by `SecurityErrorResponses` (same Spanish messages as
`GlobalExceptionHandler`). An unexpected exception is logged at ERROR with its stack trace and the
`traceId`; the client only reads "Ocurrió un error inesperado. Si el problema continúa, comparta el
código de referencia {traceId} con el administrador."
A lost row lock (Postgres deadlock, lock timeout: any `PessimisticLockingFailureException`) is a retryable 409 "La operación coincidió con otra al mismo tiempo. Intente de nuevo.", logged at WARN.
Framework client errors never become a 500: wrong `Content-Type` (e.g. JSON sent to a multipart
endpoint, `text/plain` to a JSON one) → 415; unacceptable `Accept` → 406; unparsable multipart → 400;
oversized upload → 413; missing header / parameter / part, type conversion failures → 400; unknown
path → 404; wrong method → 405; any other Spring `ErrorResponse` with a 4xx status keeps its status
with a generic message. Error bodies are always `application/json`, whatever `Accept` asked for.
Pagination is not needed at this scale; lists return arrays.

```
AUTH / IDENTITY
POST   /auth/register                     {fullName,email,password,schoolRelation?,academicProgram,semester?,birthDate,documentType,documentNumber,initialRole?,phoneNumber?}  → 201 UserResponse (any e-mail; PLAYER outside the age range → 409; invalid phone → 400)
POST   /auth/login                        {email,password} → {token,expiresAt,user}
POST   /auth/logout                       → 204
GET    /auth/me                           → UserResponse {id,fullName,email,phoneNumber,schoolRelation,academicProgram,semester,status,birthDate,documentType,documentNumber,roles[],hasProfile,teamId?}
GET    /admin/users?search=               ADMIN,ORGANIZER → UserResponse[] (organizers: e-mail and phone, no birth date / identity document)
GET    /admin/users/{id}                  ADMIN,ORGANIZER → UserResponse (organizers: e-mail and phone, no birth date / identity document)
DELETE /admin/users/{id}/photo            ADMIN → PlayerProfileResponse (photo removed, moderation; 409 without photo)
GET    /admin/users/{id}/roles            ADMIN → Role[]
POST   /admin/users/{id}/roles            ADMIN {role} → Role[]
DELETE /admin/users/{id}/roles/{role}     ADMIN → Role[]
POST   /admin/users/{id}/inactivate       ADMIN → UserResponse
POST   /organizer/referees                ORGANIZER,ADMIN {fullName,email,password,birthDate,documentType,documentNumber} → 201 UserResponse
GET    /organizer/referees                ORGANIZER,ADMIN → UserResponse[]
GET    /admin/audit?action=&limit=        ADMIN → AuditLogResponse[] (limit 1..500, default 100; otherwise 400)

USERS & PLAYERS
GET    /users/{id}                        auth → UserResponse (personal data incl. phone only for self/ADMIN)
PATCH  /users/{id}                        self|ADMIN {fullName,schoolRelation,academicProgram,semester,phoneNumber?} → UserResponse (non-admin changing schoolRelation → 403; phoneNumber absent = keep, "" = remove, invalid → 400)
GET    /players/me/profile                PLAYER → PlayerProfileResponse {userId,fullName,position,jerseyNumber,photoFileId,teamId?,teamName?,teamLocked}
PUT    /players/me/profile                PLAYER {position,jerseyNumber} → PlayerProfileResponse (create or update; jersey change → 409 while the team is locked)
POST   /players/me/profile/photo          PLAYER multipart "file" → PlayerProfileResponse (always allowed)
DELETE /players/me/profile/photo          PLAYER → PlayerProfileResponse (always allowed; 409 without photo)
GET    /players/{userId}/profile          auth → PlayerProfileResponse
GET    /players?position=&available=true  CAPTAIN,ORGANIZER → PlayerProfileResponse[] (ACTIVE users holding PLAYER only)
POST   /teams/{teamId}/join-requests      PLAYER {message?} → 201 JoinRequestResponse {id,teamId,teamName,playerId,playerName,position,jerseyNumber,status,direction,message,createdAt}
GET    /players/me/join-requests          PLAYER → JoinRequestResponse[] (direction REQUEST only)
POST   /join-requests/{id}/cancel         PLAYER (owner) → JoinRequestResponse
GET    /teams/{teamId}/join-requests?status=PENDING   CAPTAIN (of team) → JoinRequestResponse[] (direction REQUEST only)
POST   /join-requests/{id}/accept         CAPTAIN → JoinRequestResponse   (INVITATION rows → 409, also for reject/cancel)
POST   /join-requests/{id}/reject         CAPTAIN → JoinRequestResponse
POST   /teams/{teamId}/invitations        CAPTAIN (of team)|ADMIN {playerId,message?} → 201 JoinRequestResponse (direction INVITATION)
GET    /teams/{teamId}/invitations?status=PENDING|ACCEPTED|REJECTED|CANCELLED   CAPTAIN (of team)|ADMIN → JoinRequestResponse[] newest first
GET    /players/me/invitations            PLAYER → JoinRequestResponse[] (every status, newest first)
POST   /invitations/{id}/accept           invited player → JoinRequestResponse (joins the team)
POST   /invitations/{id}/reject           invited player → JoinRequestResponse
POST   /invitations/{id}/cancel           CAPTAIN (of team)|ADMIN → JoinRequestResponse

TEAMS
POST   /teams                             PLAYER {name,colors} → 201 TeamResponse (creator becomes CAPTAIN) {id,name,colors,status,captain{id,fullName},members[{userId,fullName,position,jerseyNumber,academicProgram,photoFileId}],memberCount,locked}
GET    /teams                             auth → TeamResponse[] (summary)
GET    /teams/mine                        auth → TeamResponse | 404
GET    /teams/{id}                        auth → TeamResponse
PATCH  /teams/{id}                        CAPTAIN {name?,colors?} → TeamResponse
DELETE /teams/{id}/members/{userId}       CAPTAIN → TeamResponse
POST   /teams/{id}/leave                  member (not the captain) → 204 (409 captain or locked team, 403 non-member)
POST   /teams/{id}/inactivate             CAPTAIN|ADMIN → TeamResponse
DELETE /teams/{id}                        ADMIN → 204 (409 while it plays a match or holds a live registration)
GET    /teams/{id}/eligibility            auth → {eligible, problems[]}

TOURNAMENTS
GET    /tournaments                       public → TournamentResponse[] {id,name,startDate,endDate,registrationDeadline,maxTeams,fee,status,rulebookFileId,venues[],approvedTeams,suspendedFromStatus} (SUSPENDED only for ADMIN/ORGANIZER)
GET    /tournaments/current               public → TournamentResponse | 404 (latest ACTIVE or IN_PROGRESS)
GET    /tournaments/{id}                  public → TournamentResponse
POST   /tournaments                       ORGANIZER {name,startDate,endDate,registrationDeadline,maxTeams,fee} → 201
PATCH  /tournaments/{id}                  ORGANIZER (DRAFT) → TournamentResponse
DELETE /tournaments/{id}                  ORGANIZER (DRAFT) → 204
POST   /tournaments/{id}/activate|start|finish   ORGANIZER → TournamentResponse
POST   /tournaments/{id}/suspend|resume   ADMIN,ORGANIZER → TournamentResponse
POST   /tournaments/{id}/hard-delete      ADMIN {confirmName} → 204 (any status; name mismatch → 400)
POST   /tournaments/{id}/rulebook         ORGANIZER multipart "file" (pdf) → TournamentResponse
POST   /tournaments/{id}/venues           ORGANIZER multipart {name,description,file?} → 201 VenueResponse {id,name,description,imageFileId}
DELETE /tournaments/{id}/venues/{venueId} ORGANIZER → 204
PUT    /tournaments/{id}/venues/{venueId}/image   ORGANIZER multipart "file" → VenueResponse (DRAFT|ACTIVE|IN_PROGRESS)
DELETE /tournaments/{id}/venues/{venueId}/image   ORGANIZER → VenueResponse (DRAFT|ACTIVE|IN_PROGRESS)
GET    /tournaments/{id}/players/export   ADMIN → .xlsx attachment (any status; sheets "Aprobados", "En revisión")
POST   /tournaments/{id}/registrations    CAPTAIN multipart "file" → 201 RegistrationResponse {id,tournamentId,teamId,teamName,receiptFileId,status,reviewNote,createdAt,reviewedAt}
GET    /tournaments/{id}/registrations    ORGANIZER → RegistrationResponse[]
GET    /tournaments/{id}/registrations/mine   CAPTAIN → RegistrationResponse | 404
POST   /registrations/{id}/approve        ORGANIZER {note?} → RegistrationResponse
POST   /registrations/{id}/reject         ORGANIZER {note?} → RegistrationResponse
POST   /registrations/{id}/cancel         CAPTAIN → RegistrationResponse

COMPETITION
GET    /tournaments/{id}/groups           public → GroupResponse[] {id,name,position,teams[{id,name,colors,hasMatches}],matchCount}
POST   /tournaments/{id}/groups           ORGANIZER (ACTIVE|IN_PROGRESS, no knockout) → 201 GroupResponse
DELETE /tournaments/{id}/groups/{groupId} ORGANIZER → 204 (no team, no match)
PUT    /tournaments/{id}/groups/{groupId}/teams/{teamId}     ORGANIZER → GroupResponse (assign or move an approved team)
DELETE /tournaments/{id}/groups/{groupId}/teams/{teamId}     ORGANIZER → GroupResponse
POST   /tournaments/{id}/groups/{groupId}/round-robin        ORGANIZER (IN_PROGRESS) → 201 MatchResponse[] (missing pairings only)
POST   /tournaments/{id}/groups/round-robin  ORGANIZER (IN_PROGRESS) → {created,groups[{groupId,name,teams,created}],matches,message} ("Generar partidos", every group; idempotent)
DELETE /tournaments/{id}/matches/{matchId}   ORGANIZER (IN_PROGRESS, knockout not started) → 204 (GROUP match SCHEDULED|CANCELLED only)
POST   /tournaments/{id}/matches          ORGANIZER (IN_PROGRESS) {groupId,homeTeamId,awayTeamId,scheduledAt,venueId?,refereeId?} → 201 MatchResponse
GET    /tournaments/{id}/knockout/suggestion?size=2|4|8      ORGANIZER → {phase,size,source:GROUPS|GENERAL,pairings[{home,away:{teamId,teamName,label,groupId}}]}
POST   /tournaments/{id}/knockout         ORGANIZER {pairings[{homeTeamId,awayTeamId}]} (1, 2 or 4) → 201 MatchResponse[]
POST   /tournaments/{id}/matches/advance  ORGANIZER → MatchResponse[] (next knockout round by hand; never from the group stage)
POST   /tournaments/{id}/matches/undo-phase  ORGANIZER (IN_PROGRESS) → {phase, deletedMatches}
GET    /tournaments/{id}/matches?phase=   public → MatchResponse[] {id,tournamentId,phase,roundNumber,group{id,name}?,homeTeam{id,name,colors},awayTeam{...},venue{id,name}?,referee{id,fullName}?,scheduledAt,status,homeScore,awayScore,homePenalties,awayPenalties,cancelReason,walkoverWinnerTeamId,resultEditable,teamsEditable,reopenable,nextMatchId,nextSlot,events[{id,teamId,playerId,playerName,type,minute}]}
GET    /matches/{id}                      public → MatchResponse
PATCH  /matches/{id}                      ORGANIZER (IN_PROGRESS) {scheduledAt?,venueId?,refereeId?,homeTeamId?,awayTeamId?} → MatchResponse
DELETE /matches/{id}?reason=NO_SHOW&winnerTeamId=   ORGANIZER → MatchResponse (CANCELLED; winnerTeamId required for knockout)
POST   /matches/{id}/result               ORGANIZER {homeScore,awayScore,homePenalties?,awayPenalties?,events[{teamId,playerId,type,minute?}]} → MatchResponse (record or correct)
POST   /matches/{id}/reopen               ORGANIZER (IN_PROGRESS) → MatchResponse (PLAYED|CANCELLED → SCHEDULED)
PUT    /matches/{id}/lineups              CAPTAIN {formation,starterIds[]} → LineupResponse {matchId,teamId,formation,starters[{userId,fullName,position,jerseyNumber}],substitutes[...]}
GET    /matches/{id}/lineups/{teamId}     team members|ORGANIZER|ADMIN|match referee → LineupResponse | 404
GET    /tournaments/{id}/standings        public → StandingRow[] {position,teamId,teamName,played,won,drawn,lost,goalsFor,goalsAgainst,goalDifference,points}
GET    /tournaments/{id}/standings/groups public → [{groupId,name,rows:StandingRow[]}] (empty without groups)
GET    /tournaments/{id}/bracket          public → {phases:[{phase,matches:MatchResponse[]}]}
GET    /tournaments/{id}/stats/top-scorers   public → [{playerId,playerName,teamId,teamName,goals}]
GET    /tournaments/{id}/stats/history       public → MatchResponse[] (PLAYED, by date desc)

HISTORY (FINISHED tournaments)
GET    /tournaments/history               public → [{id,name,startDate,endDate,podiumSource,champion{teamId,teamName}?}] (latest end first)
GET    /tournaments/{id}/history          public → {tournamentId,name,startDate,endDate,podiumSource,podium[{position,teamId,teamName,played,points,goalDifference,goalsFor,goalsAgainst,photoFileId}],thirdPlaceCandidates[...],bestDefence{teamId,teamName,goalsConceded,matchesPlayed}?,topScorers[{playerId,playerName,teamId,teamName,goals}],sanctionedTeams[{id,teamId?,teamName,note,createdAt,members[{fullName,captain}]}],participants[{id,name}]} (409 unless FINISHED)
POST   /tournaments/{id}/history/sanctions              ORGANIZER {teamId,note?} → 201 SanctionedTeam
DELETE /tournaments/{id}/history/sanctions/{sanctionId} ORGANIZER → 204
PUT    /tournaments/{id}/history/podium/{position}/photo   ORGANIZER multipart "file" → {position,teamId,fileId}
DELETE /tournaments/{id}/history/podium/{position}/photo   ORGANIZER → 204
GET    /tournaments/{id}/history/podium/{position}/photo   public → image
GET    /tournaments/{id}/teams/{teamId}/results  public → MatchResponse[]
GET    /referees/me/matches               REFEREE → MatchResponse[]
GET    /matches/{id}/sanctioned-players   REFEREE|ORGANIZER|ADMIN → [{userId,fullName,teamId,teamName,reason}]

FILES & HOME
GET    /files/{id}                        auth → binary (Content-Type from GridFS metadata)
GET    /home                              auth → {tournament?:TournamentResponse, myTeam?:TeamResponse, myRegistration?:RegistrationResponse, upcomingMatches:MatchResponse[], standingsTop:StandingRow[]}
```

OpenAPI via springdoc at `/swagger-ui.html`.

## 4. Frontend

```
frontend/src/
  app/            router.tsx, providers.tsx, App.tsx
  lib/            api client (fetch wrapper with bearer token, error normalization), formatters
  store/          auth.store.ts (Zustand, persisted: token + user), ui.store.ts
  types/          api.ts (mirrors DTOs above)
  components/     atomic design
    atoms/        Button, Input, Select, Badge, Spinner, Avatar, FileInput
    molecules/    FormField, Card, StatTile, Modal, Table, EmptyState, Alert
    organisms/    Navbar, Sidebar, StandingsTable, BracketView, MatchCard, TeamRoster, LineupPitch
    templates/    AppShell, AuthLayout
  features/       one folder per backend module; container (hooks/pages) vs presentational split
    auth/         LoginPage, RegisterPage, useAuth
    home/         HomePage
    players/      ProfilePage, FreeAgentsPage, JoinRequestsPage (requests + invitations)
    teams/        MyTeamPage, TeamsPage, TeamDetailPage, TeamRequestsPage
    tournaments/  TournamentsPage (tabs "Torneos" / "Torneos anteriores" `?view=past`), TournamentDetailPage (tabs: info,
                  standings, bracket, matches, stats, history "Historial" when FINISHED, manage), PublicHistoryPage
                  (`/history`, `/history/:id`, no session),
                  OrganizerTournamentPage (manage, registrations review), RegisterTeamPage
    competition/  MatchDetailPage, LineupPage, ResultFormPage, RefereeMatchesPage
    admin/        UsersPage (roles), AuditPage, UserDetailPage (`/users/:id`, ADMIN/ORGANIZER: account data,
                  sport profile, team; ADMIN "Quitar foto")
```

- Route guards by role (`RequireRole`). Navbar adapts to roles.
- Team rosters: for ADMIN/ORGANIZER every member name links to `/users/:id`; an ADMIN also gets "Quitar foto" (ConfirmDialog) on members with a photo. Players keep the plain roster.
- Contact phone: optional "Celular" field (hint "Para que los organizadores puedan contactarlo.") in the register form and in `BasicInfoForm` (profile "Información básica" and the ADMIN "Editar datos" modal), validated with the backend rule and message and sent normalised. Staff see it in the users directory, in `/users/:id` (`tel:` link) and, on the team page, next to the captain (fetched from `GET /admin/users/{captainId}`; never requested for other roles).
- Captain phone banner (`features/players/components/MissingPhoneBanner`, `needsPhoneBanner` in `features/players/phoneField.ts`): a user who literally holds CAPTAIN (ADMIN does not imply it) and has no phone sees a warning `Alert` ("Agregue su número de celular") below the navbar on every authenticated page (`AppShell` `banner` slot, aligned with the content), with "Agregar mi celular" → `/profile#celular`, which scrolls to and focuses the field. It cannot be dismissed; saving the phone updates the session user (PATCH answer + `refreshMe`) so it disappears without a reload.
- Profile page: photo (upload/remove) and position always editable; the jersey field is disabled with "No puede cambiar su dorsal mientras su equipo juega un torneo." while `teamLocked`.
- Tournament "Gestión": card "Partidos de grupo" with "Generar partidos" (ConfirmDialog; disabled with the server's reason via `generateAllState`: not in progress, suspended, knockout started, no group with 2 teams, unassigned teams listed, nothing missing); each group lists its matches ("Partidos del grupo (n)") with "Eliminar" for those without result (ConfirmDialog, then `refreshCompetition` reloads matches and groups); the match page also offers "Eliminar partido" for such a match. "Finalizar torneo" navigates to `?tab=history` with router state `justFinished`, where staff see a hint that sanctioned teams and podium photos can be added now or later.
- History tab / public page (`HistoryTab`): podium cards (a team with a photo opens it in a modal, public URL `podiumPhotoUrl`), semi-final losers compared for 3rd, best defence, top scorers, sanctioned teams with members; ADMIN/ORGANIZER get "Agregar foto"/"Cambiar foto" (modal with "Quitar foto") and the sanction form / "Retirar". A FINISHED tournament card opens its history.
- Tournament "Gestión": venues get "Cambiar foto"/"Agregar foto" (modal, also "Quitar foto") while not SUSPENDED/FINISHED; ADMIN gets "Descargar Excel de jugadores" (`downloadBlob` → blob + object URL, file name from `Content-Disposition`).
- Server state: simple custom hooks with `useEffect` + api client (no extra library); Zustand only for session and UI.
- Tailwind v4 via `@tailwindcss/vite`. Mobile-first, one accent color, consistent spacing.
- Files render through `GET /api/files/{id}` with the bearer token (fetch → blob URL helper `useFileUrl`).
- Vite dev proxy `/api` → `http://localhost:8080`.

## 5. Local development

```
docker compose up -d            # postgres on host 5433, mongo on host 27018
cd backend && ./mvnw spring-boot:run
cd frontend && pnpm install && pnpm dev
```

Env (backend `application.yml` with env overrides): `DB_URL, DB_USER, DB_PASSWORD, MONGO_URI, JWT_SECRET, JWT_EXPIRATION_MINUTES, CORS_ORIGINS, APP_TIME_ZONE, APP_PLAYER_MIN_AGE, APP_PLAYER_MAX_AGE, ADMIN_EMAIL, ADMIN_PASSWORD`.

## 6. Testing strategy

- Backend: unit tests for application services and domain rules (JUnit 5 + Mockito), focused on the business rules in 3.4 (eligibility, state transitions, standings, bracket generation, sanctions). One `@SpringBootTest` context test using Testcontainers is optional; not required for the grade.
- Frontend: Vitest + Testing Library for critical presentational components and the auth store.
