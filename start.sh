#!/usr/bin/env bash
#
# Starts the whole TechCup Futbol stack (PostgreSQL, MongoDB, backend, frontend) with Docker.
# Usage, from the project folder:  ./start.sh
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is not installed or not on the PATH."
  echo "Install Docker Desktop (macOS) or Docker Engine with the Compose plugin (Linux) and try again."
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  echo "Docker is installed but not running."
  echo "Start Docker Desktop (or the Docker service) and try again."
  exit 1
fi

echo
echo "Building and starting TechCup Futbol."
echo "The first run downloads images and dependencies and can take several minutes."
echo
if ! docker compose --profile app up -d --build; then
  echo
  echo "Docker Compose could not build or start the services. See the messages above."
  echo "A common cause is a port already in use: 5173, 8080, 5433 or 27018."
  exit 1
fi

echo
echo "Waiting for the backend to be ready..."
ready=false
for _ in $(seq 1 90); do
  if curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 2
done

if [[ "$ready" != true ]]; then
  echo
  echo "The containers started but the backend did not answer within 3 minutes."
  echo "Check its logs with:  docker compose logs backend"
  exit 1
fi

cat <<'EOF'

============================================================
 TechCup Futbol is running

 Web app:  http://localhost:5173
 API:      http://localhost:8080/api
 Swagger:  http://localhost:8080/swagger-ui.html

 Administrator: admin@escuelaing.edu.co / Admin123*

 To stop it:  docker compose --profile app down
============================================================

EOF

if command -v xdg-open >/dev/null 2>&1; then
  xdg-open http://localhost:5173 >/dev/null 2>&1 || true
elif command -v open >/dev/null 2>&1; then
  open http://localhost:5173 || true
fi
