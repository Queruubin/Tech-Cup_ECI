# Running TechCup Fútbol locally

Step-by-step instructions to run the databases, the backend and the frontend on your own machine,
and to load demo data for testing. Follow the sections in order the first time.

## Quick path

| # | Terminal | Command | Ready when |
|---|---|---|---|
| 1 | any, project root | `docker compose up -d` | `docker compose ps` shows both services `healthy` |
| 2 | terminal A, `backend/` | `.\mvnw.cmd spring-boot:run` (Windows) or `./mvnw spring-boot:run` | the log shows `Started TechcupApplication` |
| 3 | terminal B, `frontend/` | `pnpm install` then `pnpm dev` | the log shows `http://localhost:5173` |
| 4 | browser | open http://localhost:5173 | the login page appears |
| 5 | optional, Git Bash, project root | `bash scripts/seed-demo.sh` | it prints `Demo data ready` and the demo accounts |

Terminals A and B must stay open while you use the app.

## 1. Prerequisites

Install these once.

| Tool | Version | Check with | Needed for |
|---|---|---|---|
| Docker Desktop | any recent | `docker compose version` | PostgreSQL and MongoDB |
| Java JDK | **17** | `java -version` | backend |
| Node.js | **22** (LTS) | `node -v` | frontend |
| pnpm | **10** | `pnpm -v` (install with `npm install -g pnpm@10`) | frontend |
| Git Bash | included with Git for Windows | `bash --version` | demo data script (Windows only) |
| Python | **3.x** | `python --version` | demo data script |
| curl | built into Windows 10/11, macOS, Linux | `curl --version` | demo data script |

Maven is **not** required: the backend uses the Maven wrapper (`mvnw`) included in the repo.

> **Windows and Python:** `python --version` must print `Python 3.x`. If it opens the Microsoft
> Store instead, install Python from python.org with "Add python.exe to PATH" checked, and turn off
> the `python.exe` alias in *Settings → Apps → Advanced app settings → App execution aliases*.

The first run needs internet: Docker downloads the database images, Maven downloads the backend
dependencies and pnpm downloads the frontend packages. Later runs start much faster.

## 2. Databases

From the project root:

```bash
docker compose up -d
```

This starts only the two databases:

| Service | Host port | Stores |
|---|---|---|
| PostgreSQL 16 | **5433** | all relational data (users, teams, tournaments, matches...) |
| MongoDB 7 | **27018** | uploaded files (photos, venue images, payment receipts, rulebook) |

The ports are not the usual 5432 and 27017 on purpose: many machines already run PostgreSQL or
MongoDB locally, and those would take the connection instead of the containers.

Check that both are healthy before continuing:

```bash
docker compose ps
```

Docker Desktop must be open and running, otherwise every `docker` command fails.

## 3. Backend

In a new terminal, from the `backend/` folder.

Windows (PowerShell or CMD):

```powershell
.\mvnw.cmd spring-boot:run
```

macOS, Linux or Git Bash:

```bash
./mvnw spring-boot:run
```

On startup Flyway creates the database schema and a single administrator account. No other
configuration is needed: the defaults already point to the databases from step 2.

It is ready when the log shows `Started TechcupApplication`. To confirm, open
http://localhost:8080/actuator/health, which must return `{"status":"UP"}`.

| URL | What it is |
|---|---|
| http://localhost:8080/api | REST API |
| http://localhost:8080/swagger-ui.html | interactive API documentation |

## 4. Frontend

In another terminal, from the `frontend/` folder:

```bash
pnpm install
pnpm dev
```

`pnpm install` is only needed the first time and whenever dependencies change.

Open http://localhost:5173. The frontend forwards every `/api` request to the backend on port
8080, so the backend from step 3 must be running.

Log in with the administrator account:

| Email | Password |
|---|---|
| `admin@escuelaing.edu.co` | `Admin123*` |

At this point the database is empty except for that administrator.

## 5. Demo data (optional)

The seed script fills the empty database with a complete tournament so the app can be tested
without filling in every form by hand. It works through the REST API, so **the databases and the
backend must already be running**. The frontend does not need to be running.

On Windows, run it from **Git Bash** (not PowerShell or CMD). From the project root:

```bash
bash scripts/seed-demo.sh
```

It takes about a minute and creates:

- 1 organizer and 2 referees.
- 4 teams of 7 players each, with sport profiles and a captain.
- The tournament "Torneo TechCup 2026-2", with a rulebook, 2 venues and the 4 registrations approved.
- The group stage fixture list, with results, goals and cards for the first rounds. The last
  round is left scheduled so results, lineups and rescheduling can be tested from the app.

When it finishes it prints the accounts it created. Every demo account uses the password
**`Demo1234*`**. The emails include a number that changes on every run, for example:

| Role | Email pattern |
|---|---|
| Organizer | `organizador<number>@escuelaing.edu.co` |
| Captains | `capitan1.<number>@escuelaing.edu.co` … `capitan4.<number>@escuelaing.edu.co` |
| Players | `jugador1.<number>@escuelaing.edu.co` … `jugador27.<number>@escuelaing.edu.co` |
| Referees | `arbitro1.<number>@escuelaing.edu.co`, `arbitro2.<number>@escuelaing.edu.co` |

> **Run it once per database.** Team names must be unique, so a second run on the same data stops
> with an error when it tries to create the teams. To load the demo data again, reset the
> database first (section 7).

## 6. Stopping

| What | How |
|---|---|
| Frontend | `Ctrl+C` in terminal B |
| Backend | `Ctrl+C` in terminal A |
| Databases, keeping the data | `docker compose down` |

Next time, repeat steps 2 to 4. The data is still there.

## 7. Resetting the data

This deletes **all** data, including uploaded files, and returns to a clean database.

1. Stop the backend (`Ctrl+C` in terminal A).
2. From the project root, delete the databases and start them again:

   ```bash
   docker compose down -v
   docker compose up -d
   ```

3. Start the backend again (step 3). Flyway recreates the schema and the administrator.
4. Optionally, run the demo data script again (step 5).

The backend must be restarted after a reset: the schema is only created at startup.

## 8. It works when

- [ ] `docker compose ps` shows `postgres` and `mongo` as `healthy`.
- [ ] http://localhost:8080/actuator/health returns `{"status":"UP"}`.
- [ ] http://localhost:5173 shows the login page.
- [ ] Logging in as `admin@escuelaing.edu.co` opens the home page.
- [ ] After the seed, *Torneos* lists "Torneo TechCup 2026-2" as *En progreso*.

## 9. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `docker` commands fail with a connection or pipe error | Docker Desktop is not running | Open Docker Desktop and wait until it reports it is running. |
| `port is already allocated` when starting the databases | another program uses port 5433 or 27018 | Stop that program, or the other containers using those ports. |
| Backend: `password authentication failed for user "techcup"` | the backend reached a different PostgreSQL, usually because a `DB_URL` variable points to port 5432 | Remove the `DB_URL` environment variable so the default (port 5433) is used. |
| Backend: `JAVA_HOME is not set` or `release version 17 not supported` | Java 17 is missing or not the active JDK | Install JDK 17 and make `java -version` report 17. |
| Backend: `Connection refused` to PostgreSQL or MongoDB | the databases are not running or not healthy yet | Run step 2 and wait for `healthy` in `docker compose ps`. |
| `./mvnw: Permission denied` (macOS or Linux) | the wrapper is not executable | Run `chmod +x mvnw` once. |
| The web page loads but every action fails | the backend is not running on port 8080 | Start the backend (step 3) and reload the page. |
| Seed: `cannot log in as admin@escuelaing.edu.co -- is the backend running` | the backend is not running or not ready | Wait for `Started TechcupApplication` and run the script again. |
| Seed: `python: command not found`, or the Microsoft Store opens | Python 3 is not installed or not on the PATH | See the Windows note in section 1. |
| Seed stops with an error while creating the teams | the demo data was already loaded | Reset the data (section 7) before running it again. |
