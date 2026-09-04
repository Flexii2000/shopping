#!/usr/bin/env bash
set -euo pipefail

# Deployt eine neue Version der Einkaufsliste (fherrmann.com/shopping-list).
#
#     ssh -t HeimServerRemote '~/services/shopping/deploy/update-shopping.sh'
#
# Baut auf dem Server aus ~/services/shopping und installiert nach /opt/shopping.
# Der Build laeuft als flexii, nur die Installation braucht Root - deshalb
# NICHT das ganze Skript mit sudo starten (das -t braucht sudo fuers Passwort).
#
# /opt/shopping/data/ wird NICHT angefasst: shopping.json ist der Live-Bestand.

BUILD_DIR="$HOME/services/shopping"
APP_DIR="/opt/shopping"
TARGET="$APP_DIR/app.jar"
JAR="$BUILD_DIR/build/libs/Shopping-0.0.1-SNAPSHOT.jar"
JAVA="/opt/java/jdk-25.0.1+8/bin/java"
PORT=48220

[[ $EUID -ne 0 ]] || { echo "Bitte OHNE sudo starten - das Skript ruft sudo selbst auf." >&2; exit 1; }

echo "[1/5] git pull ..."
git -C "$BUILD_DIR" pull --ff-only

echo "[2/5] Jar bauen ..."
(cd "$BUILD_DIR" && JAVA_HOME="$(dirname "$(dirname "$JAVA")")" ./gradlew bootJar --quiet)
[[ -f "$JAR" ]] || { echo "    FEHLER: $JAR fehlt." >&2; exit 1; }

echo "[3/5] Laufendes Jar sichern ..."
BACKUP="$TARGET.bak-$(date +%Y%m%d-%H%M%S)"
sudo cp -p "$TARGET" "$BACKUP"
echo "    Backup: $BACKUP"

echo "[4/5] Installieren und neu starten ..."
sudo install -o shopping -g shopping -m 644 "$JAR" "$TARGET"
sudo systemctl restart shopping

echo "[5/5] Health-Check ..."
for i in $(seq 1 30); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$PORT/shopping-list/api/board" || true)"
    # Ohne Token ist 401 die richtige Antwort; jeder Status heisst "App bedient".
    if [[ "$code" != "000" ]]; then
        echo "    OK (HTTP $code)"
        echo "shopping erfolgreich aktualisiert."
        exit 0
    fi
    sleep 1
done

echo "    FEHLER: App antwortet nicht. Rollback auf $BACKUP ..." >&2
sudo install -o shopping -g shopping -m 644 "$BACKUP" "$TARGET"
sudo systemctl restart shopping
echo "    Zurueckgerollt. Logs: journalctl -u shopping -n 50" >&2
exit 1
