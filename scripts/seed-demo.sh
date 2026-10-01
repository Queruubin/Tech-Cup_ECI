#!/usr/bin/env bash
#
# Seeds the local development database with a realistic demo tournament so the
# application can be explored (or demonstrated) without clicking through every
# form by hand.
#
# It talks to the running REST API only -- it never writes to the database
# directly -- so it also works as an end-to-end smoke test of the whole flow:
# registration, roles, sport profiles, teams (whose creators become captains),
# join requests, invitations, tournament lifecycle, payment review, fixtures,
# results and standings.
#
# Usage:
#   docker compose up -d
#   (cd backend && ./mvnw spring-boot:run)      # or: java -jar backend/target/*.jar
#   ./scripts/seed-demo.sh
#
# Every demo account uses the password below.
set -euo pipefail

API="${API:-http://localhost:8080/api}"
PASSWORD="Demo1234*"
ADMIN_EMAIL="admin@escuelaing.edu.co"
ADMIN_PASSWORD="Admin123*"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ---------------------------------------------------------------- helpers ---

fail() { echo "ERROR: $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }

# json <token> <method> <path> <body>
json() {
  local token="$1" method="$2" path="$3" body="${4:-}"
  local args=(-sS -X "$method" "$API$path" -H 'Content-Type: application/json')
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$body" ]] && args+=(-d "$body")
  curl "${args[@]}"
}

# upload <token> <path> <file> [extra field=value ...]
upload() {
  local token="$1" path="$2" file="$3"; shift 3
  local args=(-sS -X POST "$API$path" -H "Authorization: Bearer $token" -F "file=@$file")
  for extra in "$@"; do args+=(-F "$extra"); done
  curl "${args[@]}"
}

pick() { python -c "import sys,json;d=json.load(sys.stdin);print(d$1)"; }

login() {
  local email="$1" password="${2:-$PASSWORD}"
  json '' POST /auth/login "{\"email\":\"$email\",\"password\":\"$password\"}" | pick "['token']"
}

# Players must be between 5 and 100 years old (app.player.min-age / max-age), so
# every PLAYER account is born exactly ten years before today; everybody else is
# an adult. Python keeps the date arithmetic portable across GNU and BSD `date`.
PLAYER_BIRTH_DATE="$(python -c "import datetime as d; t=d.date.today(); print(t.replace(year=t.year-10) if not (t.month==2 and t.day==29) else t.replace(year=t.year-10, day=28))")"
ADULT_BIRTH_DATE="1988-03-15"

# register <email> <full name> <relation> <program> <semester|null> <role> [birth date]
register() {
  local email="$1" name="$2" relation="$3" program="$4" semester="$5" role="$6"
  local birth="${7:-$PLAYER_BIRTH_DATE}"
  local doc=$((RANDOM * 1000 + RANDOM))
  json '' POST /auth/register "$(cat <<JSON
{"fullName":"$name","email":"$email","password":"$PASSWORD",
 "schoolRelation":"$relation","academicProgram":"$program","semester":$semester,
 "birthDate":"$birth","documentType":"CC","documentNumber":"$doc","initialRole":"$role"}
JSON
)" | pick "['id']"
}

# --------------------------------------------------------------- fixtures ---

# A minimal but valid one-page PDF, used as the tournament rulebook.
printf '%%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj\ntrailer<</Root 1 0 R>>\n%%%%EOF\n' > "$TMP/reglamento.pdf"
# A 1x1 PNG, used as venue image and payment receipt.
printf '\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\nIDATx\x9cc\x00\x01\x00\x00\x05\x00\x01\r\n-\xb4\x00\x00\x00\x00IEND\xaeB`\x82' > "$TMP/imagen.png"

POSITIONS=(GOALKEEPER DEFENDER DEFENDER MIDFIELDER MIDFIELDER FORWARD FORWARD)
NAMES=(
  "Santiago Ramirez" "Mateo Gutierrez" "Nicolas Herrera" "Samuel Castro"
  "Juan Diego Rojas" "Andres Felipe Mora" "Tomas Vargas" "Emilio Pardo"
  "Daniel Quintero" "Sebastian Nieto" "Martin Osorio" "Julian Bermudez"
  "Camilo Restrepo" "Alejandro Pineda" "Diego Salazar" "Felipe Cardenas"
  "Ricardo Amaya" "Esteban Lozano" "Ivan Trujillo" "Oscar Beltran"
  "Laura Camacho" "Valentina Rincon" "Sara Montoya" "Isabella Duque"
  "Mariana Escobar" "Daniela Ospina" "Antonia Guerrero" "Sofia Valencia"
)
TEAM_NAMES=("Los Compiladores" "Kernel Panic" "Deep Learners" "Firewall FC")
TEAM_COLORS=("verde/blanco" "negro/rojo" "azul/dorado" "naranja/gris")
PROGRAMS=(SYSTEMS_ENGINEERING AI_ENGINEERING CYBERSECURITY_ENGINEERING STATISTICS_ENGINEERING)

SUFFIX="$(date +%s | tail -c 5)"

# ------------------------------------------------------------------ start ---

step "Authenticating as the system administrator"
ADMIN_TOKEN="$(login "$ADMIN_EMAIL" "$ADMIN_PASSWORD")"
[[ -n "$ADMIN_TOKEN" ]] || fail "cannot log in as $ADMIN_EMAIL -- is the backend running on $API?"
echo "ok"

step "Creating the tournament organizer"
ORG_EMAIL="organizador$SUFFIX@escuelaing.edu.co"
ORG_ID="$(register "$ORG_EMAIL" "Olivia Organizadora" ADMINISTRATIVE OTHER null GUEST "$ADULT_BIRTH_DATE")"
json "$ADMIN_TOKEN" POST "/admin/users/$ORG_ID/roles" '{"role":"ORGANIZER"}' > /dev/null
ORG_TOKEN="$(login "$ORG_EMAIL")"
echo "organizer #$ORG_ID -> $ORG_EMAIL"

step "Creating referees"
for i in 1 2; do
  json "$ORG_TOKEN" POST /organizer/referees "$(cat <<JSON
{"fullName":"Arbitro $i","email":"arbitro$i.$SUFFIX@escuelaing.edu.co","password":"$PASSWORD",
 "birthDate":"1985-06-0$i","documentType":"CC","documentNumber":"90$SUFFIX$i"}
JSON
)" > /dev/null
  echo "referee -> arbitro$i.$SUFFIX@escuelaing.edu.co"
done

step "Creating 4 teams with 7 players each"
# Captains are not appointed: a player who creates a team becomes its captain.
# Roles are read from the database on every request, so the captain's token keeps
# working for the captain-only calls right after the team is created.
declare -a CAPTAIN_TOKENS TEAM_IDS
player=0
for t in 0 1 2 3; do
  cap_email="capitan$((t + 1)).$SUFFIX@escuelaing.edu.co"
  register "$cap_email" "${NAMES[$player]}" STUDENT "${PROGRAMS[$t]}" 7 PLAYER > /dev/null
  cap_token="$(login "$cap_email")"
  json "$cap_token" PUT /players/me/profile "{\"position\":\"GOALKEEPER\",\"jerseyNumber\":1}" > /dev/null
  team_id="$(json "$cap_token" POST /teams \
    "{\"name\":\"${TEAM_NAMES[$t]}\",\"colors\":\"${TEAM_COLORS[$t]}\"}" | pick "['id']")"
  CAPTAIN_TOKENS[$t]="$cap_token"
  TEAM_IDS[$t]="$team_id"
  player=$((player + 1))

  # Six more players ask to join, the captain accepts each request.
  for p in 1 2 3 4 5 6; do
    email="jugador$player.$SUFFIX@escuelaing.edu.co"
    # Keep more than half of every squad in the four eligible programs.
    prog="${PROGRAMS[$((p % 4))]}"
    register "$email" "${NAMES[$player]}" STUDENT "$prog" $((p + 2)) PLAYER > /dev/null
    tok="$(login "$email")"
    json "$tok" PUT /players/me/profile \
      "{\"position\":\"${POSITIONS[$p]}\",\"jerseyNumber\":$((p + 1))}" > /dev/null
    req_id="$(json "$tok" POST "/teams/$team_id/join-requests" \
      '{"message":"Quiero unirme al equipo"}' | pick "['id']")"
    json "$cap_token" POST "/join-requests/$req_id/accept" '' > /dev/null
    player=$((player + 1))
  done
  echo "team #$team_id ${TEAM_NAMES[$t]} -> captain $cap_email (7 players)"
done

step "Creating the tournament"
TODAY="$(date +%Y-%m-%d)"
END="$(date -d '+45 days' +%Y-%m-%d 2>/dev/null || date -v+45d +%Y-%m-%d)"
TOURNAMENT_ID="$(json "$ORG_TOKEN" POST /tournaments "$(cat <<JSON
{"name":"Torneo TechCup 2026-2","startDate":"$TODAY","endDate":"$END",
 "registrationDeadline":"$TODAY","maxTeams":8,"fee":80000}
JSON
)" | pick "['id']")"
upload "$ORG_TOKEN" "/tournaments/$TOURNAMENT_ID/rulebook" "$TMP/reglamento.pdf" > /dev/null
upload "$ORG_TOKEN" "/tournaments/$TOURNAMENT_ID/venues" "$TMP/imagen.png" \
  "name=Cancha Principal" "description=Cancha sintetica principal de la sede" > /dev/null
upload "$ORG_TOKEN" "/tournaments/$TOURNAMENT_ID/venues" "$TMP/imagen.png" \
  "name=Cancha Norte" "description=Cancha auxiliar del costado norte" > /dev/null
json "$ORG_TOKEN" POST "/tournaments/$TOURNAMENT_ID/activate" '' > /dev/null
echo "tournament #$TOURNAMENT_ID activated with a rulebook and 2 venues"

step "Registering the teams and approving their payments"
for t in 0 1 2 3; do
  reg_id="$(upload "${CAPTAIN_TOKENS[$t]}" "/tournaments/$TOURNAMENT_ID/registrations" \
    "$TMP/imagen.png" | pick "['id']")"
  if [[ $t -lt 3 ]]; then
    json "$ORG_TOKEN" POST "/registrations/$reg_id/approve" \
      '{"note":"Comprobante verificado"}' > /dev/null
    echo "registration #$reg_id ${TEAM_NAMES[$t]} -> APPROVED"
  else
    echo "registration #$reg_id ${TEAM_NAMES[$t]} -> left UNDER_REVIEW on purpose"
  fi
done
# A fourth approved team is needed for the semifinals.
LAST_REG="$(json "$ORG_TOKEN" GET "/tournaments/$TOURNAMENT_ID/registrations" \
  | pick "[-1]['id']")"
json "$ORG_TOKEN" POST "/registrations/$LAST_REG/approve" '{"note":"Comprobante verificado"}' > /dev/null
echo "registration #$LAST_REG -> APPROVED (4 teams ready)"

step "Starting the tournament and generating the fixture list"
json "$ORG_TOKEN" POST "/tournaments/$TOURNAMENT_ID/start" '' > /dev/null
json "$ORG_TOKEN" POST "/tournaments/$TOURNAMENT_ID/matches/generate" '' > /dev/null
MATCH_COUNT="$(json '' GET "/tournaments/$TOURNAMENT_ID/matches" | python -c "import sys,json;print(len(json.load(sys.stdin)))")"
echo "tournament IN_PROGRESS with $MATCH_COUNT group matches"

step "Recording results for the first rounds"
json '' GET "/tournaments/$TOURNAMENT_ID/matches?phase=GROUP" > "$TMP/matches.json"
python - "$TMP/matches.json" "$TMP/results.txt" <<'PY'
import json, sys, random
matches = json.load(open(sys.argv[1], encoding='utf-8'))
matches.sort(key=lambda m: (m['roundNumber'], m['id']))
random.seed(7)
# Leave the last round unplayed so the UI also shows scheduled matches.
last_round = max(m['roundNumber'] for m in matches)
with open(sys.argv[2], 'w', encoding='utf-8') as out:
    for m in matches:
        if m['roundNumber'] == last_round:
            continue
        home, away = random.randint(0, 4), random.randint(0, 3)
        out.write(f"{m['id']}\t{m['homeTeam']['id']}\t{m['awayTeam']['id']}\t{home}\t{away}\n")
PY

while IFS=$'\t' read -r match_id home_id away_id home_score away_score; do
  events="$(python - "$home_id" "$away_id" "$home_score" "$away_score" "$API" "$ADMIN_TOKEN" <<'PY'
import json, sys, urllib.request, random
home_id, away_id, home_score, away_score, api, token = sys.argv[1:7]
random.seed(int(home_id) * 31 + int(away_id))

def roster(team_id):
    req = urllib.request.Request(f"{api}/teams/{team_id}",
                                 headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(req) as response:
        return [m["userId"] for m in json.load(response)["members"]]

events = []
for team_id, goals in ((home_id, int(home_score)), (away_id, int(away_score))):
    players = roster(team_id)
    for _ in range(goals):
        events.append({"teamId": int(team_id), "playerId": random.choice(players),
                       "type": "GOAL", "minute": random.randint(1, 50)})
    if random.random() < 0.5:
        events.append({"teamId": int(team_id), "playerId": random.choice(players),
                       "type": "YELLOW_CARD", "minute": random.randint(1, 50)})
print(json.dumps(events))
PY
)"
  json "$ORG_TOKEN" POST "/matches/$match_id/result" "$(cat <<JSON
{"homeScore":$home_score,"awayScore":$away_score,"events":$events}
JSON
)" > /dev/null
done < "$TMP/results.txt"
echo "recorded $(wc -l < "$TMP/results.txt" | tr -d ' ') results with goals and cards"

step "Sending a demo invitation"
# The four tournament teams are registered in a tournament in progress, so their rosters are
# frozen. The invitation comes from a fifth team that is not registered, so it can be accepted.
RESERVE_CAPTAIN="capitan5.$SUFFIX@escuelaing.edu.co"
register "$RESERVE_CAPTAIN" "Rodrigo Reserva" STUDENT SYSTEMS_ENGINEERING 6 PLAYER > /dev/null
RESERVE_TOKEN="$(login "$RESERVE_CAPTAIN")"
json "$RESERVE_TOKEN" PUT /players/me/profile '{"position":"GOALKEEPER","jerseyNumber":1}' > /dev/null
RESERVE_TEAM_ID="$(json "$RESERVE_TOKEN" POST /teams '{"name":"Reserva FC","colors":"blanco/rojo"}' | pick "['id']")"
FREE_EMAIL="libre.$SUFFIX@escuelaing.edu.co"
FREE_ID="$(register "$FREE_EMAIL" "Lucas Libre" FAMILY OTHER null PLAYER)"
FREE_TOKEN="$(login "$FREE_EMAIL")"
json "$FREE_TOKEN" PUT /players/me/profile '{"position":"FORWARD","jerseyNumber":10}' > /dev/null
INVITATION_ID="$(json "$RESERVE_TOKEN" POST "/teams/$RESERVE_TEAM_ID/invitations" \
  "{\"playerId\":$FREE_ID,\"message\":\"Te queremos en Reserva FC\"}" | pick "['id']")"
echo "invitation #$INVITATION_ID: Reserva FC invited free player $FREE_EMAIL (pending)"

step "Demo data ready"
cat <<EOF

Tournament #$TOURNAMENT_ID "Torneo TechCup 2026-2" is IN_PROGRESS.

  Web:     http://localhost:5173
  API:     $API
  Swagger: http://localhost:8080/swagger-ui.html

Accounts (password for every demo account: $PASSWORD)

  Administrator  $ADMIN_EMAIL            ($ADMIN_PASSWORD)
  Organizer      $ORG_EMAIL
  Captains       capitan1.$SUFFIX@escuelaing.edu.co ... capitan4.$SUFFIX@escuelaing.edu.co
  Players        jugador1.$SUFFIX@escuelaing.edu.co ... jugador27.$SUFFIX@escuelaing.edu.co
  Free player    $FREE_EMAIL  (pending invitation from Reserva FC)
  Reserve team   $RESERVE_CAPTAIN  (captain of Reserva FC, not registered)
  Referees       arbitro1.$SUFFIX@escuelaing.edu.co, arbitro2.$SUFFIX@escuelaing.edu.co

The last group round is still scheduled, so results, lineups and rescheduling
can all be exercised from the interface.
EOF
