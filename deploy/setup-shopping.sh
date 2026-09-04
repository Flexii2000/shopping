#!/usr/bin/env bash
set -euo pipefail

# Erstinstallation der Einkaufsliste (fherrmann.com/shopping-list) auf dem
# Heimserver.
#
# Vom Laptop aus, das -t ist noetig (sudo fragt nach dem Passwort):
#
#     ssh -t HeimServerRemote '~/services/shopping/deploy/setup-shopping.sh'
#
# Beim allerersten Mal liegt das Repo noch nicht auf dem Server - dann:
#
#     ssh HeimServerRemote 'git clone git@github.com:Flexii2000/shopping.git ~/services/shopping'
#
# Idempotent: jeder Schritt prueft erst, ob er schon erledigt ist. Die Token
# werden nur beim ersten Lauf erzeugt; eine vorhandene /etc/shopping.env
# bleibt, wie sie ist.
#
# Was es NICHT braucht: DNS, Zertifikat, Privat-Token. Der Dienst liegt als
# Pfad unter fherrmann.com und prueft eigene Token - je Person einer, damit
# eine zweite Person nur die Liste bekommt und sonst nichts.

BUILD_DIR="$HOME/services/shopping"
APP_DIR="/opt/shopping"
SERVICE_USER="shopping"
PORT=48220
JAVA="/opt/java/jdk-25.0.1+8/bin/java"
ENV_FILE="/etc/shopping.env"
NGINX_CONF="/etc/nginx/sites-available/fherrmann.com"
PEOPLE=(felix freundin)

step() { echo; echo "=== $* ==="; }
fail() { echo "FEHLER: $*" >&2; exit 1; }

# Konfiguration pruefen und nginx neu einlesen - mit Warteschleife, weil der
# taegliche certbot-Lauf nginx fuer ein paar Sekunden stoppt (siehe
# setup-food.sh, dort ist das ausfuehrlich begruendet).
nginx_apply() {
    sudo nginx -t
    for attempt in $(seq 1 30); do
        if systemctl is-active --quiet nginx; then
            sudo systemctl reload nginx
            return 0
        fi
        [[ $attempt -eq 1 ]] && echo "    nginx laeuft gerade nicht - vermutlich certbot. Warte ..."
        sleep 2
    done
    fail "nginx ist seit 60 s nicht aktiv. Status: systemctl status nginx"
}

[[ $EUID -eq 0 ]] && fail "Bitte NICHT mit sudo starten - das Skript ruft sudo selbst auf, wo es noetig ist."
[[ -d "$BUILD_DIR/.git" ]] || fail "$BUILD_DIR fehlt - erst klonen (siehe Kopf dieses Skripts)."

step "1/7 Repo aktualisieren"
git -C "$BUILD_DIR" pull --ff-only

step "2/7 Jar bauen"
(cd "$BUILD_DIR" && JAVA_HOME="$(dirname "$(dirname "$JAVA")")" ./gradlew bootJar --quiet)
JAR="$BUILD_DIR/build/libs/Shopping-0.0.1-SNAPSHOT.jar"
[[ -f "$JAR" ]] || fail "$JAR wurde nicht gebaut."
echo "    $(du -h "$JAR" | cut -f1)"

step "3/7 Service-User und Verzeichnisse"
if ! id -u "$SERVICE_USER" >/dev/null 2>&1; then
    sudo useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin "$SERVICE_USER"
    echo "    User $SERVICE_USER angelegt."
else
    echo "    User $SERVICE_USER existiert bereits."
fi
sudo mkdir -p "$APP_DIR/data"
sudo chown -R "$SERVICE_USER:$SERVICE_USER" "$APP_DIR"

step "4/7 Token -> $ENV_FILE"
if sudo test -f "$ENV_FILE"; then
    echo "    $ENV_FILE existiert - Token bleiben, wie sie sind."
else
    # Je Person ein eigener Token. Der Name davor steht spaeter an den
    # Eintraegen; aendern darf man ihn jederzeit in der Datei.
    tokens=""
    for person in "${PEOPLE[@]}"; do
        tokens+="${tokens:+,}${person}:$(openssl rand -hex 24)"
    done
    printf 'SHOPPING_TOKENS=%s\n' "$tokens" | sudo tee "$ENV_FILE" >/dev/null
    echo "    Token fuer ${PEOPLE[*]} erzeugt."
fi
sudo chown root:"$SERVICE_USER" "$ENV_FILE"
sudo chmod 640 "$ENV_FILE"
TOKENS="$(sudo grep -E '^SHOPPING_TOKENS=' "$ENV_FILE" | cut -d= -f2-)"
[[ -n "$TOKENS" ]] || fail "Kein SHOPPING_TOKENS in $ENV_FILE."

step "5/7 Jar und systemd-Unit"
sudo install -o "$SERVICE_USER" -g "$SERVICE_USER" -m 644 "$JAR" "$APP_DIR/app.jar"
sudo cp "$BUILD_DIR/deploy/shopping.service" /etc/systemd/system/shopping.service
sudo systemctl daemon-reload
sudo systemctl enable shopping
# restart statt "enable --now": beim zweiten Lauf bliebe das neue Jar sonst
# ungenutzt, und das Skript meldete trotzdem Erfolg.
sudo systemctl restart shopping
sleep 3
sudo systemctl is-active --quiet shopping || {
    sudo journalctl -u shopping -n 30 --no-pager >&2
    fail "shopping.service laeuft nicht - Log siehe oben."
}
echo "    shopping.service laeuft."

step "6/7 nginx: /shopping-list/ unter fherrmann.com"
if grep -q "location /shopping-list/" "$NGINX_CONF"; then
    echo "    Schon eingebunden."
else
    sudo cp "$NGINX_CONF" "$NGINX_CONF.bak.$(date +%s)"
    # Direkt vor den /grades/-Block: gleiche Ebene, gleiche Bauart.
    grep -q "location /grades/" "$NGINX_CONF" || fail "Kein /grades/-Block in $NGINX_CONF - wo soll /shopping-list/ hin?"
    python3 - "$NGINX_CONF" "$BUILD_DIR/deploy/nginx-shopping.conf" <<'PY'
import sys, pathlib, subprocess
conf, snippet = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]).read_text()
text = conf.read_text()
marker = "      location /grades/ {"
assert text.count(marker) == 1, "der /grades/-Block steht nicht genau einmal da"
neu = text.replace(marker, snippet + marker)
subprocess.run(["sudo", "tee", str(conf)], input=neu.encode(), check=True, stdout=subprocess.DEVNULL)
PY
    nginx_apply
    echo "    Eingebunden und nginx neu geladen."
fi

step "7/7 Health-Check"
for i in $(seq 1 30); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$PORT/shopping-list/api/board" || true)"
    [[ "$code" != "000" ]] && break
    sleep 1
done
[[ "${code:-000}" != "000" ]] || fail "App antwortet nicht. Log: journalctl -u shopping -n 50"
[[ "$code" == "401" ]] || echo "    HINWEIS: ohne Token kam HTTP $code statt 401."
# Mit dem ersten Token muss es 200 sein - das prueft Datei und App-Pruefung zugleich.
FIRST_TOKEN="${TOKENS%%,*}"; FIRST_TOKEN="${FIRST_TOKEN#*:}"
authed="$(curl -s --max-time 10 -H "Authorization: Bearer $FIRST_TOKEN" "http://127.0.0.1:$PORT/shopping-list/api/board" || true)"
echo "$authed" | grep -q '"items"' || fail "Mit Token kam kein Brett - Antwort: ${authed:0:200}"
echo "    Brett mit Token: $(echo "$authed" | grep -o '"me":"[^"]*"')"

echo
echo "Fertig. Die Links, die im Browser das Cookie setzen (einmal je Geraet):"
IFS=',' read -ra pairs <<< "$TOKENS"
for pair in "${pairs[@]}"; do
    name="${pair%%:*}"; token="${pair#*:}"
    echo "    $name:  https://fherrmann.com/shopping-list/setup?token=$token"
done
echo
echo "In der App (Healthy bzw. Einkauf) den Token im Zugang-Blatt eintragen - ohne den Namen davor."
echo "Namen aendern: $ENV_FILE, danach sudo systemctl restart shopping."
echo "Spaetere Updates: ssh -t HeimServerRemote '~/services/shopping/deploy/update-shopping.sh'"
