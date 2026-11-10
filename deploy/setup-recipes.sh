#!/usr/bin/env bash
set -euo pipefail

# Richtet die Rezepte der Einkaufsliste ein: den Claude-Agenten des Rezept-Chats,
# seine sudo-Regel, das Drop-in fuer die Unit, die Eintraege in /etc/shopping.env
# und den Dienst-Token, mit dem "Kochen" ein Gericht im Kalorienzaehler anlegt.
#
# Aufruf VOM LAPTOP aus - das -t ist noetig, sonst kann sudo nicht nach dem
# Passwort fragen und das Skript bricht ohne Ausgabe ab:
#
#     ssh -t HeimServerRemote '~/services/shopping/deploy/setup-recipes.sh'
#
# In der Healthy-Runde (November 2026) ruft es der Wrapper setup-healthy-plus.sh als
# dritten Schritt in EINEM ssh -t auf - deshalb kein `sudo -k` hier: das Ticket der
# Sitzung soll fuer alle drei Skripte reichen.
#
# Voraussetzung: die Einkaufsliste laeuft schon mit einem Stand, der die Rezepte
# kennt - also vorher ~/services/shopping/deploy/update-shopping.sh. Der
# Kalorienzaehler sollte den internen Endpunkt /api/internal/recipe-dishes kennen;
# ohne ihn geht alles ausser "Kochen legt das Gericht in Healthy an".
#
# Idempotent: jeder Schritt prueft erst, ob er schon erledigt ist. Secrets stehen nie
# auf einer Kommandozeile und nie in der Ausgabe.

BUILD_DIR="$HOME/services/shopping"
# Arbeitsverzeichnis der Chat-Session. Wie die Agenten des Kalorienzaehlers NEBEN
# dem Repo und nicht darin: dort stehen die Rechte, und weder ein Deploy noch der
# Agent selbst sollen sie verschieben koennen.
AGENT_DIR="$HOME/services/recipe-agent"
SHOPPING_ENV="/etc/shopping.env"
FOOD_ENV="/etc/food.env"
SUDOERS_TARGET="/etc/sudoers.d/25-recipe-agent"
DROPIN_DIR="/etc/systemd/system/shopping.service.d"
DROPIN="$DROPIN_DIR/10-agent.conf"
SERVICE_USER="shopping"
PORT=48220
FOOD_PORT=48180
API="http://127.0.0.1:$PORT/shopping-list/api"

step() { echo; echo "=== $* ==="; }
fail() { echo "FEHLER: $*" >&2; exit 1; }

# Eine Zeile KEY=... in einer env-Datei setzen oder anhaengen. Der Wert geht ueber
# die Umgebung an awk, nicht als Argument - eine Kommandozeile steht in der
# Prozessliste, und hier laufen Token durch. Ueber tee statt mv, damit Besitzer und
# Rechte der Datei (root:<dienst> 640) erhalten bleiben.
set_env() {
    local file="$1" key="$2" value="$3" current updated
    current="$(sudo cat "$file")"
    if grep -qE "^${key}=" <<<"$current"; then
        updated="$(ENV_VALUE="$value" awk -v k="$key" \
            'index($0, k "=") == 1 { print k "=" ENVIRON["ENV_VALUE"]; next } { print }' <<<"$current")"
    else
        updated="$(printf '%s\n%s=%s' "$current" "$key" "$value")"
    fi
    printf '%s\n' "$updated" | sudo tee "$file" >/dev/null
}

get_env() {
    sudo grep -E "^$2=" "$1" | tail -1 | cut -d= -f2- || true
}

has_env() {
    sudo grep -qE "^$2=" "$1"
}

# Ein Aufruf mit Token, ohne dass der Token auf der Kommandozeile von curl steht:
# der Header kommt als Konfiguration ueber die Standardeingabe (printf ist in der
# Shell eingebaut, also kein eigener Prozess). Ausgabe: Rumpf, dann eine Zeile Status.
with_token() {
    local token="$1"; shift
    printf 'header = "Authorization: Bearer %s"\n' "$token" \
        | curl -s --max-time 10 -K - -w '\n%{http_code}' "$@" || true
}

wait_for_port() {
    local port="$1" path="$2" name="$3" code="000"
    for _ in $(seq 1 60); do
        code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$port$path" || true)"
        [[ "$code" != "000" ]] && return 0
        sleep 1
    done
    fail "$name antwortet nicht. Log: journalctl -u $name -n 50"
}

# Ob der Dienstnutzer ein Kommando als flexii starten duerfte - gefragt, nicht
# ausprobiert (sudo -l fuehrt nichts aus).
may_run() {
    sudo -u "$SERVICE_USER" sudo -n -l -u flexii "$@" >/dev/null 2>&1
}

[[ $EUID -eq 0 ]] && fail "Bitte NICHT mit sudo starten - das Skript ruft sudo selbst auf, wo es noetig ist."
[[ -d "$BUILD_DIR/deploy/recipe-agent" ]] \
    || fail "$BUILD_DIR/deploy/recipe-agent fehlt - erst ~/services/shopping/deploy/update-shopping.sh (holt und installiert den neuen Stand)."
sudo test -f "$SHOPPING_ENV" || fail "$SHOPPING_ENV fehlt - ist die Einkaufsliste eingerichtet (setup-shopping.sh)?"

TOKENS="$(get_env "$SHOPPING_ENV" SHOPPING_TOKENS)"
[[ -n "$TOKENS" ]] || fail "Kein SHOPPING_TOKENS in $SHOPPING_ENV."
FIRST_TOKEN="${TOKENS%%,*}"; FIRST_TOKEN="${FIRST_TOKEN#*:}"
# Die Namen hinter den Token, mit Komma - wer die Liste benutzt, bekommt den Chat.
PEOPLE="$(tr ',' '\n' <<<"$TOKENS" | cut -d: -f1 | paste -sd, -)"

step "0/8 Kennt der laufende Dienst die Rezepte?"
# Ohne Token antwortet alles unter /api/ mit 401 - auch ein Pfad, den es nicht gibt.
# Also mit Token fragen: ein alter Stand sagt 404.
chat_before="$(with_token "$FIRST_TOKEN" "$API/chat")"
[[ "${chat_before##*$'\n'}" == "200" ]] \
    || fail "Der laufende Dienst kennt /api/chat nicht (HTTP ${chat_before##*$'\n'}) - erst ~/services/shopping/deploy/update-shopping.sh."
echo "    Ja."

step "1/8 Agent-Verzeichnis $AGENT_DIR"
mkdir -p "$AGENT_DIR/.claude"
cp "$BUILD_DIR/deploy/recipe-agent/CLAUDE.md" "$AGENT_DIR/CLAUDE.md"
cp "$BUILD_DIR/deploy/recipe-agent/run-agent.sh" "$AGENT_DIR/run-agent.sh"
cp "$BUILD_DIR/deploy/recipe-agent/.claude/settings.json" "$AGENT_DIR/.claude/settings.json"
chmod +x "$AGENT_DIR/run-agent.sh"
# Niemand ausser flexii soll hier schreiben koennen: wer die settings.json aendert,
# aendert die Rechte der Session.
chmod -R go-w "$AGENT_DIR"
echo "    $AGENT_DIR eingerichtet."

# Wie bei den Agenten des Kalorienzaehlers: ein vertrautes Verzeichnis, sonst meldet
# Claude Code "this workspace has not been trusted". Nur eintragen, wenn es fehlt;
# geschrieben wird daneben und dann umbenannt - laufende Sessions lesen dieselbe Datei.
python3 - "$AGENT_DIR" <<'TRUST'
import json, os, pathlib, shutil, sys, datetime, tempfile
agent_dir = sys.argv[1]
config = pathlib.Path.home() / ".claude.json"
if not config.exists():
    print("    HINWEIS: ~/.claude.json fehlt - Vertrauen nicht gesetzt.")
    raise SystemExit
data = json.loads(config.read_text())
if data.get("projects", {}).get(agent_dir, {}).get("hasTrustDialogAccepted"):
    print(f"    Workspace {agent_dir} ist schon vertrauenswuerdig.")
    raise SystemExit
shutil.copy2(config, config.with_suffix(f".json.bak-{datetime.datetime.now():%Y%m%d-%H%M%S}"))
data.setdefault("projects", {}).setdefault(agent_dir, {})["hasTrustDialogAccepted"] = True
fd, temp = tempfile.mkstemp(dir=str(config.parent), prefix=".claude.json.")
with os.fdopen(fd, "w") as out:
    json.dump(data, out, indent=2)
os.chmod(temp, config.stat().st_mode & 0o777)
os.replace(temp, config)
print(f"    Workspace {agent_dir} als vertrauenswuerdig eingetragen.")
TRUST

step "2/8 sudo-Regel $SUDOERS_TARGET"
# Erst pruefen, dann installieren: eine kaputte Datei unter /etc/sudoers.d/ legt
# sudo auf dem ganzen System lahm.
sudo visudo -c -q -f "$BUILD_DIR/deploy/sudoers-recipe-agent" \
    || fail "sudoers-Regel ist fehlerhaft - nichts installiert."
sudo install -o root -g root -m 440 "$BUILD_DIR/deploy/sudoers-recipe-agent" "$SUDOERS_TARGET"
# Und das Ganze noch einmal: die neue Datei darf mit keiner vorhandenen kollidieren.
if ! sudo visudo -c -q; then
    sudo rm -f "$SUDOERS_TARGET"
    fail "sudoers ist mit der neuen Regel fehlerhaft - $SUDOERS_TARGET wieder entfernt."
fi
echo "    $SUDOERS_TARGET installiert."

step "3/8 Drop-in $DROPIN"
# Die Unit selbst bleibt, wie sie ist (deploy/shopping.service steht mit
# NoNewPrivileges=true im Repo, und setup-shopping.sh kopiert sie). Das Drop-in
# nimmt nur diese eine Zeile zurueck - ohne das sperrt systemd sudo im Dienst.
if sudo test -f "$DROPIN" && sudo grep -qx 'NoNewPrivileges=false' "$DROPIN"; then
    echo "    Schon da."
else
    sudo mkdir -p "$DROPIN_DIR"
    printf '%s\n' \
        "# Der Rezept-Chat startet seine Claude-Session ueber sudo als flexii" \
        "# (/etc/sudoers.d/25-recipe-agent). NoNewPrivileges unterbaende das." \
        "# Angelegt von ~/services/shopping/deploy/setup-recipes.sh." \
        "[Service]" \
        "NoNewPrivileges=false" | sudo tee "$DROPIN" >/dev/null
    sudo chmod 644 "$DROPIN"
    sudo systemctl daemon-reload
    echo "    Angelegt, systemd neu eingelesen."
fi

step "4/8 Darf $SERVICE_USER den Agenten starten?"
# Gegenprobe, ohne eine (kostende) Session zu starten: run-agent.sh --version zeigt
# nur die Version von claude, das beweist sudo, Nutzer und Binary auf einmal. Diese
# Probe laeuft AUSSERHALB der Unit; ob es auch im Dienst klappt, zeigt Schritt 8
# (der Dienst probiert es beim Start selbst und meldet es in /api/chat).
if VERSION="$(sudo -u "$SERVICE_USER" sudo -n -u flexii "$AGENT_DIR/run-agent.sh" --version </dev/null 2>/dev/null)"; then
    AGENT_COMMAND="/usr/bin/sudo -n -u flexii $AGENT_DIR/run-agent.sh"
    echo "    Ja (${VERSION%%$'\n'*})."
else
    AGENT_COMMAND=""
    echo "    HINWEIS: die sudo-Ausnahme greift nicht - Rezepte laufen so lange ohne Chat."
fi

step "5/8 Eintraege in $SHOPPING_ENV"
set_env "$SHOPPING_ENV" SHOPPING_RECIPE_AGENT_COMMAND "$AGENT_COMMAND"
echo "    SHOPPING_RECIPE_AGENT_COMMAND=${AGENT_COMMAND:-(leer)}"
if has_env "$SHOPPING_ENV" SHOPPING_RECIPE_CHAT; then
    echo "    SHOPPING_RECIPE_CHAT bleibt: $(get_env "$SHOPPING_ENV" SHOPPING_RECIPE_CHAT)"
else
    set_env "$SHOPPING_ENV" SHOPPING_RECIPE_CHAT "$PEOPLE"
    echo "    SHOPPING_RECIPE_CHAT=$PEOPLE"
fi
if has_env "$SHOPPING_ENV" FOOD_URL; then
    echo "    FOOD_URL bleibt: $(get_env "$SHOPPING_ENV" FOOD_URL)"
else
    set_env "$SHOPPING_ENV" FOOD_URL "http://127.0.0.1:$FOOD_PORT"
    echo "    FOOD_URL=http://127.0.0.1:$FOOD_PORT"
fi

step "6/8 Dienst-Token fuer \"Kochen\" (Einkaufsliste -> Kalorienzaehler)"
# Ein eigener Token, kein persoenlicher: er kann beim Kalorienzaehler genau eines -
# ein Gericht anlegen (POST /api/internal/recipe-dishes). Beide Dienste kennen
# denselben Wert; erzeugt wird er einmal und steht dann in beiden env-Dateien.
RESTART_FOOD=0
RECIPE_TOKEN=""
if sudo test -f "$FOOD_ENV"; then
    RECIPE_TOKEN="$(get_env "$FOOD_ENV" FOOD_RECIPE_TOKEN)"
    if [[ -z "$RECIPE_TOKEN" ]]; then
        RECIPE_TOKEN="$(openssl rand -hex 32)"
        set_env "$FOOD_ENV" FOOD_RECIPE_TOKEN "$RECIPE_TOKEN"
        RESTART_FOOD=1
        echo "    FOOD_RECIPE_TOKEN in $FOOD_ENV neu erzeugt."
    else
        echo "    FOOD_RECIPE_TOKEN in $FOOD_ENV ist schon da."
    fi
    if [[ "$(get_env "$SHOPPING_ENV" SHOPPING_HEALTHY_TOKEN)" == "$RECIPE_TOKEN" ]]; then
        echo "    SHOPPING_HEALTHY_TOKEN in $SHOPPING_ENV passt dazu."
    else
        set_env "$SHOPPING_ENV" SHOPPING_HEALTHY_TOKEN "$RECIPE_TOKEN"
        echo "    SHOPPING_HEALTHY_TOKEN in $SHOPPING_ENV gesetzt (derselbe Wert)."
    fi
else
    echo "    HINWEIS: $FOOD_ENV fehlt - \"Kochen\" legt so lange nichts in Healthy an."
fi
sudo chown root:"$SERVICE_USER" "$SHOPPING_ENV"
sudo chmod 640 "$SHOPPING_ENV"

step "7/8 Dienste neu starten"
if [[ $RESTART_FOOD -eq 1 ]]; then
    # Nur wenn der Token neu ist - der Kalorienzaehler liest ihn beim Start.
    sudo systemctl restart food
    wait_for_port "$FOOD_PORT" "/" food
    echo "    food laeuft."
fi
# Immer: Drop-in und env-Datei gelten erst nach einem Neustart.
sudo systemctl restart shopping
wait_for_port "$PORT" "/shopping-list/api/board" shopping
echo "    shopping laeuft."

step "8/8 Pruefung"
systemctl is-active --quiet shopping || fail "shopping.service laeuft nicht."
[[ "$(systemctl show -p NoNewPrivileges --value shopping)" == "no" ]] \
    || fail "shopping.service laeuft noch mit NoNewPrivileges - greift das Drop-in $DROPIN?"
echo "    Unit: aktiv, NoNewPrivileges=no."

# Die Ausnahme soll genau ein Kommando oeffnen - nichts sonst als flexii, und keinen
# der anderen Agenten.
may_run /bin/true && fail "$SERVICE_USER darf /bin/true als flexii starten - die sudo-Regel ist zu weit."
for other in food-agent story-agent health-agent; do
    if [[ -e "$HOME/services/$other/run-agent.sh" ]] && may_run "$HOME/services/$other/run-agent.sh"; then
        fail "$SERVICE_USER darf den Agenten $other starten - die sudo-Regeln ueberschneiden sich."
    fi
done
if [[ -n "$AGENT_COMMAND" ]]; then
    may_run "$AGENT_DIR/run-agent.sh" || fail "$SERVICE_USER darf den eigenen Agenten nicht starten."
fi
echo "    sudo: nur der eigene Agent, sonst nichts."

[[ "$(stat -c '%U' "$AGENT_DIR")" == "flexii" ]] || fail "$AGENT_DIR gehoert nicht flexii."
[[ -z "$(find "$AGENT_DIR" -perm /022 -print -quit)" ]] || fail "In $AGENT_DIR darf noch jemand ausser flexii schreiben."
echo "    Agent-Verzeichnis: gehoert flexii, sonst schreibt niemand."

code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$API/board" || true)"
[[ "$code" == "401" ]] || fail "Ohne Token kam HTTP $code statt 401."
board="$(with_token "$FIRST_TOKEN" "$API/board")"
grep -q '"items"' <<<"$board" || fail "Mit Token kam kein Brett (HTTP ${board##*$'\n'})."
echo "    API: ohne Token 401, mit Token das Brett."

# Der Dienst probiert beim Start selbst, ob er den Agenten starten darf - unter den
# echten Bedingungen der Unit. Das dauert einen Augenblick.
available=""
for _ in $(seq 1 20); do
    chat="$(with_token "$FIRST_TOKEN" "$API/chat")"
    if grep -q '"available":true' <<<"$chat"; then
        available="ja"
        break
    fi
    sleep 1
done
if [[ -n "$available" ]]; then
    echo "    Rezept-Chat: eingerichtet (der Dienst erreicht den Agenten)."
elif [[ -z "$AGENT_COMMAND" ]]; then
    echo "    HINWEIS: Rezept-Chat aus - die sudo-Ausnahme greift nicht (Schritt 4)."
else
    fail "Der Dienst meldet den Rezept-Chat als nicht eingerichtet, obwohl die Regel greift. Log: journalctl -u shopping -n 50 | grep -i agent"
fi

sudo -n -l /usr/local/sbin/update-service.sh shopping >/dev/null 2>&1 \
    && echo "    Deploy ohne Passwort (update-service.sh shopping): geht weiter." \
    || echo "    HINWEIS: update-service.sh shopping ist nicht ohne Passwort erlaubt - Agenten koennen nicht ausrollen."

if [[ -n "$RECIPE_TOKEN" ]]; then
    # Ein leerer Rumpf legt nichts an: 400 heisst "Token angenommen, Rumpf abgelehnt".
    probe="$(with_token "$RECIPE_TOKEN" -X POST -H 'Content-Type: application/json' -d '{}' \
        "http://127.0.0.1:$FOOD_PORT/api/internal/recipe-dishes")"
    case "${probe##*$'\n'}" in
        2??|400|422) echo "    Kalorienzaehler: nimmt den Dienst-Token an." ;;
        401|403)     echo "    HINWEIS: der Kalorienzaehler lehnt den Dienst-Token ab (HTTP ${probe##*$'\n'}) - laeuft dort ein Stand mit FOOD_RECIPE_TOKEN?" ;;
        404)         echo "    HINWEIS: der Kalorienzaehler kennt /api/internal/recipe-dishes noch nicht - erst ~/scripts/update-food.sh." ;;
        *)           echo "    HINWEIS: Kalorienzaehler antwortet mit HTTP ${probe##*$'\n'}." ;;
    esac
fi

# Alle Agenten laufen auf Felix' Claude-Login; laeuft der ab, steht auch der Chat.
python3 -c 'import json,datetime; o=json.load(open("/home/flexii/.claude/.credentials.json"))["claudeAiOauth"]; print("    Claude-Login gilt bis", datetime.datetime.fromtimestamp(o["refreshTokenExpiresAt"]/1000).strftime("%d.%m.%Y %H:%M"))' 2>/dev/null \
    || echo "    HINWEIS: Ablauf des Claude-Logins nicht lesbar."

echo
echo "Fertig. Rezepte mit Chat fuer: $PEOPLE"
echo "Modell des Chats: RECIPE_AGENT_MODEL in $SHOPPING_ENV (Vorgabe sonnet), danach sudo systemctl restart shopping."
echo "Spaetere Updates wie immer: ~/services/shopping/deploy/update-shopping.sh (frischt den Agenten mit auf)."
