# Deploying TechCup Fútbol on a Linux server

Step-by-step installation of the whole platform on one Linux server: the databases run in Docker,
the backend runs as a systemd service, and nginx serves the web app and proxies the API.
Written for **Ubuntu 22.04 / 24.04**; other distributions only differ in the package commands.

```
Internet ──► nginx :80/:443 ──► /           static web app   (/var/www/techcup)
                             └─► /api/      backend          (127.0.0.1:8080, systemd)
                                              ├─► PostgreSQL (127.0.0.1:5433, Docker)
                                              └─► MongoDB    (127.0.0.1:27018, Docker)
```

Only nginx is reachable from outside. The backend and both databases listen on `127.0.0.1`.

## Quick path

| # | Step | Section |
|---|---|---|
| 1 | Install Git, Docker, Java 17, Node 22, pnpm and nginx | [1](#1-install-the-tools) |
| 2 | Clone the repository into `/opt/techcup/src` | [2](#2-get-the-code) |
| 3 | Start PostgreSQL and MongoDB with your own passwords | [3](#3-databases) |
| 4 | Build the backend and run it as a service | [4](#4-backend) |
| 5 | Build the web app and publish it with nginx | [5](#5-frontend-and-nginx) |
| 6 | Enable HTTPS and the firewall | [6](#6-https-and-firewall) |
| 7 | Log in as administrator and change the password | [7](#7-first-login) |

Requirements: a server with **2 GB of RAM minimum (4 GB recommended)**, 10 GB of free disk, `sudo`
access, and ports 22, 80 and 443 open. A domain name is optional (an IP address works) but HTTPS
needs one.

## 1. Install the tools

```bash
sudo apt update
sudo apt install -y git nginx openjdk-17-jdk-headless curl ca-certificates
```

Docker Engine with the Compose plugin:

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable --now docker
```

Node.js 22 and pnpm 10:

```bash
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt install -y nodejs
sudo corepack enable
corepack prepare pnpm@10 --activate
```

Check every tool:

```bash
git --version && docker compose version && java -version && node -v && pnpm -v && nginx -v
```

`java -version` must report **17**. Maven is not needed: the repository includes its wrapper (`mvnw`).

## 2. Get the code

```bash
sudo mkdir -p /opt/techcup
sudo chown "$USER":"$USER" /opt/techcup
git clone <repository URL> /opt/techcup/src
cd /opt/techcup/src
```

## 3. Databases

The databases read their passwords from a `.env` file at the repository root. Create it from the
example and set **your own** values for `DB_PASSWORD`, `MONGO_USER` and `MONGO_PASSWORD`:

```bash
cp .env.example .env
nano .env
```

Generate each database password with `openssl rand -hex 24`. Use hexadecimal on purpose: the MongoDB
password also goes inside a connection URL, where characters such as `/`, `@` or `:` (which
`openssl rand -base64` produces) would break it. The backend refuses to start in production with the
development password `techcup`.

> **Set the passwords before the first start.** MongoDB creates its user only the first time its
> data volume is created. Changing `MONGO_USER` / `MONGO_PASSWORD` later has no effect unless the
> volume is deleted (which deletes the data).

Start them:

```bash
docker compose up -d
docker compose ps
```

Both services must show `healthy`. They restart automatically after a reboot
(`restart: unless-stopped`). This only starts the two databases; the backend and the web app are
installed natively in the next steps.

## 4. Backend

### 4.1 Build

```bash
cd /opt/techcup/src/backend
./mvnw -B test
./mvnw -B -DskipTests package
```

The first run downloads Maven and every dependency (a few minutes). The tests need no database.
The result is `target/techcup-0.1.0-SNAPSHOT.jar`.

### 4.2 Install the jar and a service user

```bash
sudo useradd --system --home /opt/techcup --shell /usr/sbin/nologin techcup
sudo cp target/techcup-0.1.0-SNAPSHOT.jar /opt/techcup/techcup.jar
sudo chown techcup:techcup /opt/techcup/techcup.jar
```

### 4.3 Configuration

```bash
sudo mkdir -p /etc/techcup
sudo cp /opt/techcup/src/deploy/backend.env.example /etc/techcup/backend.env
sudo nano /etc/techcup/backend.env
sudo chown root:techcup /etc/techcup/backend.env
sudo chmod 640 /etc/techcup/backend.env
```

Fill in every `<...>` value. Generate the JWT secret with:

```bash
openssl rand -base64 48
```

| Variable | Value |
|---|---|
| `DB_PASSWORD` | the same `DB_PASSWORD` you put in the repository `.env` (hexadecimal, see section 3) |
| `MONGO_URI` | `mongodb://MONGO_USER:MONGO_PASSWORD@127.0.0.1:27018/techcup?authSource=admin` with your values |
| `JWT_SECRET` | the output of `openssl rand -base64 48` |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` | the administrator account created on the first start |
| `CORS_ORIGINS` | the public URL of the site, e.g. `https://techcup.example.edu.co` |

The backend refuses to start in production while `JWT_SECRET` or `ADMIN_PASSWORD` keep their
development defaults. If a password contains special characters, URL-encode it inside `MONGO_URI`.

### 4.4 Run it as a service

```bash
sudo cp /opt/techcup/src/deploy/systemd/techcup-backend.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now techcup-backend
```

Check it:

```bash
sudo systemctl status techcup-backend
curl http://127.0.0.1:8080/actuator/health
```

The health check must return `{"status":"UP"}`. On the first start Flyway creates the database
schema and the backend creates the administrator. Logs: `journalctl -u techcup-backend -f`.

## 5. Frontend and nginx

### 5.1 Build the web app

```bash
cd /opt/techcup/src/frontend
pnpm install --frozen-lockfile
pnpm build
```

### 5.2 Publish it

```bash
sudo mkdir -p /var/www/techcup
sudo rsync -a --delete dist/ /var/www/techcup/
```

### 5.3 Configure nginx

```bash
sudo cp /opt/techcup/src/frontend/security-headers.conf /etc/nginx/snippets/techcup-security-headers.conf
sudo cp /opt/techcup/src/deploy/nginx/techcup.conf /etc/nginx/sites-available/techcup
sudo sed -i 's/SERVER_NAME/your.domain.or.ip/' /etc/nginx/sites-available/techcup
sudo ln -s /etc/nginx/sites-available/techcup /etc/nginx/sites-enabled/techcup
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl reload nginx
```

Open `http://your.domain.or.ip`: the login page must appear.

## 6. HTTPS and firewall

With a domain pointing to the server:

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d your.domain
```

Certbot adds the HTTPS configuration and renews the certificate automatically. Then set
`CORS_ORIGINS=https://your.domain` in `/etc/techcup/backend.env` and run
`sudo systemctl restart techcup-backend`.

Firewall:

```bash
sudo ufw allow OpenSSH
sudo ufw allow 'Nginx Full'
sudo ufw enable
```

## 7. First login

Log in with `ADMIN_EMAIL` / `ADMIN_PASSWORD` from `/etc/techcup/backend.env`, then change the
password from *Mi perfil → Cambiar contraseña*. The database starts empty except for that
administrator. Do **not** run `scripts/seed-demo.sh` on the production server: it creates demo
accounts with a public password.

## 8. Updating to a new version

```bash
cd /opt/techcup/src
git pull

cd backend
./mvnw -B -DskipTests package
sudo cp target/techcup-0.1.0-SNAPSHOT.jar /opt/techcup/techcup.jar
sudo systemctl restart techcup-backend

cd ../frontend
pnpm install --frozen-lockfile
pnpm build
sudo rsync -a --delete dist/ /var/www/techcup/
```

Database migrations run automatically when the backend starts. The web app needs no restart:
nginx never caches `index.html`, so users get the new version on their next page load.

## 9. Backups

```bash
mkdir -p ~/backups
set -a; . /opt/techcup/src/.env; set +a
docker compose -f /opt/techcup/src/docker-compose.yml exec -T postgres \
  pg_dump -U techcup techcup > ~/backups/techcup-$(date +%F).sql
docker compose -f /opt/techcup/src/docker-compose.yml exec -T mongo \
  mongodump -u "$MONGO_USER" -p "$MONGO_PASSWORD" --authenticationDatabase admin \
  --db techcup --archive > ~/backups/techcup-files-$(date +%F).archive
```

PostgreSQL holds all the data; MongoDB holds the uploaded files (photos, venue images, payment
receipts, rulebook). Back up both, ideally from a daily cron job, and copy them off the server.

## 10. Troubleshooting

| Symptom | Check |
|---|---|
| A user reports "Código de referencia: abc123def456" | `journalctl -u techcup-backend \| grep abc123def456` shows the full error. |
| `./mvnw: Permission denied` | `chmod +x mvnw` (the repository marks it executable; old clones may not). |
| Backend does not start: "JWT_SECRET is the development default" | Set a real `JWT_SECRET` and `ADMIN_PASSWORD` in `/etc/techcup/backend.env`. |
| Backend does not start: database password "is the development default" | Set real `DB_PASSWORD` / `MONGO_PASSWORD` in the repo `.env` (before the first `docker compose up`) and the same values in `/etc/techcup/backend.env`. |
| Backend log: `password authentication failed` | `DB_PASSWORD` in `/etc/techcup/backend.env` differs from the repository `.env`. |
| Backend log: MongoDB `Authentication failed` | `MONGO_URI` credentials differ from `.env`, or `.env` was changed after the volume was created. |
| The site loads but every action fails | `systemctl status techcup-backend` and `curl http://127.0.0.1:8080/actuator/health`. |
| `502 Bad Gateway` | The backend is down or still starting; wait 30 s and check its status. |
| Uploads fail with "El archivo supera el tamaño máximo" | Files are limited to 5 MB by design. |

Useful commands: `docker compose ps`, `docker compose logs postgres`, `sudo systemctl restart
techcup-backend`, `sudo nginx -t && sudo systemctl reload nginx`.
