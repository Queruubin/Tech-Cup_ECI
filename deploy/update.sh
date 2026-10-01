#!/usr/bin/env bash
#
# Pull-based deploy for the TechCup Docker stack (compose "app" profile).
#
# Run it on the SERVER, from anywhere: it finds the repository by its own location. It is safe to
# call by hand or from cron every few minutes:
#
#   - no new commit on the deploy branch  -> exits silently, nothing is touched;
#   - new commit                          -> git pull, rebuild, restart, wait for the backend;
#   - the new version does not come up    -> goes back to the previous commit and rebuilds it,
#                                            and does not retry the broken commit until a newer
#                                            one arrives.
#
# Data is never touched: PostgreSQL and MongoDB live in Docker volumes and Flyway migrations run
# by themselves when the backend starts. The .env file is not in git, so it is never overwritten.
#
# Usage:  bash deploy/update.sh            deploy only when there is something new
#         bash deploy/update.sh --force    rebuild and restart even without new commits
#
# Environment: DEPLOY_BRANCH (default main); DEPLOY_HEALTH_TRIES / DEPLOY_HEALTH_INTERVAL (60 x 3 s).
set -euo pipefail

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"
cd "$(dirname "${BASH_SOURCE[0]}")/.."

BRANCH="${DEPLOY_BRANCH:-main}"
FORCE=false
[[ "${1:-}" == "--force" ]] && FORCE=true

log() { echo "[$(date '+%F %T')] $*"; }

# One deploy at a time: a slow build must not overlap the next cron run.
exec 9>"/tmp/techcup-deploy.lock"
if ! flock -n 9; then
  exit 0
fi

GIT_DIR="$(git rev-parse --git-dir)"
BAD_COMMIT_FILE="$GIT_DIR/techcup-deploy-failed"

git fetch --quiet origin "$BRANCH"
CURRENT="$(git rev-parse HEAD)"
TARGET="$(git rev-parse "origin/$BRANCH")"

if [[ "$CURRENT" == "$TARGET" && "$FORCE" == false ]]; then
  exit 0
fi
if [[ "$FORCE" == false && -f "$BAD_COMMIT_FILE" && "$(cat "$BAD_COMMIT_FILE")" == "$TARGET" ]]; then
  # This exact commit already failed to start; wait for a newer one instead of looping.
  exit 0
fi

log "deploying ${CURRENT:0:7} -> ${TARGET:0:7} (branch $BRANCH)"
git merge --ff-only "origin/$BRANCH"

backend_port() {
  local port=""
  if [[ -f .env ]]; then
    port="$(grep -E '^BACKEND_HOST_PORT=' .env | tail -n1 | cut -d= -f2 || true)"
  fi
  echo "${port:-8080}"
}

wait_for_backend() {
  local port
  port="$(backend_port)"
  for _ in $(seq 1 "${DEPLOY_HEALTH_TRIES:-60}"); do
    if curl -sf "http://127.0.0.1:${port}/actuator/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep "${DEPLOY_HEALTH_INTERVAL:-3}"
  done
  return 1
}

docker compose --profile app up -d --build

if wait_for_backend; then
  rm -f "$BAD_COMMIT_FILE"
  docker image prune -f >/dev/null 2>&1 || true
  log "deployed ${TARGET:0:7}: backend healthy"
  exit 0
fi

log "FAILED: backend of ${TARGET:0:7} did not become healthy; going back to ${CURRENT:0:7}"
echo "$TARGET" > "$BAD_COMMIT_FILE"
git reset --hard "$CURRENT"
docker compose --profile app up -d --build
if wait_for_backend; then
  log "rolled back to ${CURRENT:0:7}: backend healthy again"
else
  log "ERROR: the previous version is not healthy either; check: docker compose --profile app logs backend"
fi
exit 1
