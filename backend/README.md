# TechCup Fútbol — Backend

Spring Boot 3.5 / Java 17 modular monolith. Architecture, data model and REST contract:
[`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md).

## Run locally

Requirements: JDK 17+, Docker.

```bash
# from the repository root: PostgreSQL 16 (host 5433) + MongoDB 7 (host 27018)
docker compose up -d

cd backend
./mvnw spring-boot:run          # http://localhost:8080
```

Flyway creates the schema (`V1__schema.sql`, `V3__match_walkover.sql`) on first start, and the
`AdminBootstrap` runner creates the administrator when no `ADMIN` user exists yet.

- Swagger UI: http://localhost:8080/swagger-ui.html (click *Authorize* and paste the JWT)
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- Health: http://localhost:8080/actuator/health

> **Existing development databases:** MongoDB now runs with authentication and the administrator
> is no longer seeded by Flyway. Reset the containers once with `docker compose down -v` before
> starting the databases again; otherwise the backend cannot authenticate against the old Mongo
> volume.

### Administrator bootstrap

On every start, `identity/infrastructure/AdminBootstrap` checks whether a user with the `ADMIN`
role exists. If none does, it creates one from `ADMIN_EMAIL` / `ADMIN_PASSWORD` (BCrypt-hashed,
status `ACTIVE`). With the development defaults it logs a **WARNING**:

| e-mail | password |
|---|---|
| `admin@escuelaing.edu.co` | `Admin123*` |

To use different credentials, set the two variables before the first start. Once an
administrator exists the variables are ignored; change the password through
`POST /api/auth/password` (own account) or `POST /api/admin/users/{id}/password` (as admin).

### Production profile

`SPRING_PROFILES_ACTIVE=prod` (`application-prod.yml`) disables Swagger UI and the OpenAPI
document and trusts `X-Forwarded-*` headers from the reverse proxy. `ProductionGuard` refuses to
start the profile when:

- `JWT_SECRET` is the development default or shorter than 32 bytes, or
- `ADMIN_PASSWORD` is the development default.

The `app` Docker Compose profile (used by `start.bat` / `start.sh`) runs with this profile and
reads the secrets from a `.env` file at the repository root (`cp .env.example .env`). Generate a
secret with `openssl rand -base64 48`.

## Configuration

Every setting in `src/main/resources/application.yml` has an environment-variable override:

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5433/techcup` | PostgreSQL JDBC URL |
| `DB_USER` | `techcup` | PostgreSQL user |
| `DB_PASSWORD` | `techcup` | PostgreSQL password |
| `MONGO_URI` | `mongodb://techcup:techcup@127.0.0.1:27018/techcup?authSource=admin` | MongoDB (GridFS) connection, with the dev root user of `docker-compose.yml` |
| `JWT_SECRET` | dev value (refused by `prod`) | HS256 signing key, at least 32 random bytes |
| `JWT_EXPIRATION_MINUTES` | `480` | Token lifetime |
| `ADMIN_EMAIL` | `admin@escuelaing.edu.co` | Administrator created on first start |
| `ADMIN_PASSWORD` | `Admin123*` (refused by `prod`) | Its password |
| `CORS_ORIGINS` | `http://localhost:5173` | Comma-separated allowed origins |
| `APP_INSTITUTIONAL_DOMAINS` | `escuelaing.edu.co,mail.escuelaing.edu.co` | Domains that count as institutional e-mail |
| `APP_TIME_ZONE` | `America/Bogota` | Zone for every date rule (tournament start/end, 18:00 kick-offs) |
| `SPRING_PROFILES_ACTIVE` | *(none)* | `prod` enables the production hardening above |
| `PORT` | `8080` | HTTP port |

## Security notes

- Login: after 5 failed attempts for the same e-mail or the same client address within 15
  minutes the endpoint answers `429`; failures are audited as `LOGIN_FAILED`.
- Logout revokes the presented token (`jti` claim) until its expiry; the denylist is in-memory.
- Personal data (e-mail, birth date, identity document) is returned only to the user themself or
  an administrator; organizers get the e-mail in the user and referee listings only.
- Uploads are validated by their magic bytes (PNG, JPEG, WebP, PDF) and stored with their owner;
  payment receipts are downloadable only by organizers, administrators and the uploading captain.
  Downloads carry `X-Content-Type-Options: nosniff` and PDFs are sent as attachments.

## Build and test

```bash
./mvnw -q -DskipTests compile   # compile
./mvnw -q test                  # unit tests (JUnit 5 + Mockito, no database needed)
./mvnw package                  # executable jar in target/
```

## Project layout

```
edu.escuelaing.techcup
  shared/        config, security (JWT), exception handling, audit, file storage (GridFS), home
  identity/      register, login, roles, referees, inactivation, admin bootstrap
  players/       user updates, sport profiles, free-agent search, join requests
  teams/         teams, members, eligibility
  tournaments/   tournaments, venues, rulebook, registrations
  competition/   matches, lineups, results, standings, bracket, stats
```

Each domain module has four layers: `api` (controllers + DTO records), `application`
(use cases, business rules, audit), `domain` (JPA entities, enums, pure rules) and
`infrastructure` (Spring Data repositories, adapters). Modules talk to each other only through
application services or ports (e.g. `teams.application.TeamLockPort`,
`players.application.TeamGateway`, `shared.storage.FileAccessPolicy`).

## Quick smoke test with curl

```bash
# admin login
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@escuelaing.edu.co","password":"Admin123*"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')

curl -s localhost:8080/api/auth/me -H "Authorization: Bearer $TOKEN"
```
