#!/usr/bin/env bash
set -euo pipefail

# Deployt eine neue Version der Einkaufsliste (fherrmann.com/shopping-list).
#
#     ssh HeimServerLocal '~/services/shopping/deploy/update-shopping.sh'
#
# Baut auf dem Server aus ~/services/shopping und installiert nach /opt/shopping.
# Der Build laeuft als flexii, installiert wird ueber das Root-Skript
# update-service.sh - ohne Passwort, also auch fuer Agenten. NICHT mit sudo starten.
#
# /opt/shopping/data/ wird NICHT angefasst: shopping.json ist der Live-Bestand,
# recipe-chat.json die Gespraeche des Rezept-Chats.

BUILD_DIR="$HOME/services/shopping"
JAR="$BUILD_DIR/build/libs/Shopping-0.0.1-SNAPSHOT.jar"
JAVA="/opt/java/jdk-25.0.1+8/bin/java"
STAGING="$HOME/staging/shopping"
ROOT_SCRIPT="/usr/local/sbin/update-service.sh"

[[ $EUID -ne 0 ]] || { echo "Bitte OHNE sudo starten - das Skript ruft sudo selbst auf." >&2; exit 1; }

echo "[1/5] git pull ..."
git -C "$BUILD_DIR" pull --ff-only

echo "[2/5] Jar bauen ..."
(cd "$BUILD_DIR" && JAVA_HOME="$(dirname "$(dirname "$JAVA")")" ./gradlew bootJar --quiet)
[[ -f "$JAR" ]] || { echo "    FEHLER: $JAR fehlt." >&2; exit 1; }

echo "[3/5] Agent-Dateien auffrischen ..."
# Kontext und Rechte des Rezept-Chats liegen ausserhalb des Repos, wandern aber mit
# jedem Deploy nach - sonst arbeitet der Agent nach einer veralteten CLAUDE.md
# weiter, ohne dass das irgendwo auffiele (wie beim Kalorienzaehler, update-food.sh).
# Eingerichtet wird das Verzeichnis von deploy/setup-recipes.sh; ohne es passiert
# hier nichts.
AGENT_DIR="$HOME/services/recipe-agent"
if [ -d "$AGENT_DIR" ] && [ -d "$BUILD_DIR/deploy/recipe-agent" ]; then
    mkdir -p "$AGENT_DIR/.claude"
    cp "$BUILD_DIR/deploy/recipe-agent/CLAUDE.md" "$AGENT_DIR/CLAUDE.md"
    cp "$BUILD_DIR/deploy/recipe-agent/run-agent.sh" "$AGENT_DIR/run-agent.sh"
    cp "$BUILD_DIR/deploy/recipe-agent/.claude/settings.json" "$AGENT_DIR/.claude/settings.json"
    chmod +x "$AGENT_DIR/run-agent.sh"
    chmod -R go-w "$AGENT_DIR"
    echo "    aktualisiert."
else
    echo "    ($AGENT_DIR gibt es nicht - der Rezept-Chat ist hier nicht eingerichtet.)"
fi

echo "[4/5] Jar bereitlegen ..."
mkdir -p "$STAGING"
cp "$JAR" "$STAGING/app.jar"

echo "[5/5] Installieren ..."
# Ohne Passwort ueber /usr/local/sbin/update-service.sh und die Regel
# /etc/sudoers.d/23-agent-deploy (~/scripts/setup-agent-deploy.sh, SERVER-CONTEXT.md):
# sichern, installieren, neu starten, pruefen, notfalls zurueckrollen.
sudo "$ROOT_SCRIPT" shopping
