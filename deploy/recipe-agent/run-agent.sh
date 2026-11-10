#!/usr/bin/env bash
set -uo pipefail

# Startet EINE Claude-Code-Session, die im Rezept-Chat der Einkaufsliste antwortet:
# ein paar Saetze und, wenn es passt, ein Rezept - als ein JSON-Objekt. Aufgerufen
# wird das Skript von der Einkaufsliste; der Auftrag kommt ueber die
# Standardeingabe, das Ergebnis geht nach stdout.
#
# Dieselbe Bauart wie der Story-Agent des Kalorienzaehlers
# (food/deploy/story-agent/run-agent.sh): Claude Code laedt CLAUDE.md und
# .claude/settings.json aus dem ARBEITSVERZEICHNIS, und das Verzeichnis liegt
# ausserhalb des Git-Repos - so kann weder ein Deploy noch der Agent selbst die
# Rechte verschieben. Die Aufrufparameter stehen hier fest; von aussen kommt nur
# die Standardeingabe. Argumente werden NICHT an claude durchgereicht.

AGENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PATH="$HOME/.local/bin:$PATH"

# Sonnet als Vorgabe: ein Rezept mit Mengen und geschaetzten Naehrwerten braucht kein
# groesseres Modell, und die Antwort soll in einer halben Minute da sein. Umstellbar
# ueber RECIPE_AGENT_MODEL in /etc/shopping.env - sudo reicht genau diese Variable
# durch (deploy/sudoers-recipe-agent). Weil sie damit von aussen kommt, nur in der
# Form eines Modellnamens: alles andere koennte sich als weitere Option ausgeben.
MODEL="${RECIPE_AGENT_MODEL:-sonnet}"
if [[ ! "$MODEL" =~ ^[A-Za-z0-9][A-Za-z0-9._:@-]*$ ]]; then
    echo "RECIPE_AGENT_MODEL ungueltig, nehme sonnet" >&2
    MODEL="sonnet"
fi

cd "$AGENT_DIR" || { echo "Agent-Verzeichnis nicht erreichbar" >&2; exit 1; }

command -v claude >/dev/null 2>&1 || { echo "claude nicht im PATH" >&2; exit 1; }

# Fuer die Proben (deploy/setup-recipes.sh und der Dienst selbst beim Start):
# beweist, dass sudo, Nutzer und Binary zusammenpassen, ohne eine (kostende)
# Session zu starten.
if [[ "${1:-}" == "--version" ]]; then
    exec claude --version
fi

# --tools "" nimmt der Session jedes Werkzeug schon beim Start; die deny-Liste in
# .claude/settings.json ist die zweite Schranke dahinter. Nebenbei entfallen die
# Werkzeugbeschreibungen im Systemkontext jeder frischen Session.
# --strict-mcp-config ohne --mcp-config laedt keinen einzigen MCP-Server.
# --no-session-persistence: nichts vom Gespraech bleibt unter ~/.claude liegen -
# der Dienst laeuft mit ProtectSystem=strict, das Heimatverzeichnis waere ohnehin
# nicht beschreibbar.
# KEIN --permission-mode: der Schalter kennt in neueren Fassungen andere Werte, und
# der Modus steht in .claude/settings.json ("default": was nicht erlaubt ist, wird
# abgelehnt statt nachgefragt). Ohne Werkzeuge gibt es ohnehin nichts zu erlauben.
# --output-format json legt einen Umschlag um die Antwort; der Dienst kommt mit
# beidem klar.
# --append-system-prompt-file gibt der Sitzung den Auftrag ausdruecklich mit. Darauf, dass
# Claude Code die CLAUDE.md des Arbeitsverzeichnisses von selbst laedt, ist hier kein
# Verlass: steht "Read" auf der deny-Liste (.claude/settings.json), laedt die CLI sie
# nicht (beobachtet am 10.11.2026 mit 2.1.318 - die Sitzung kannte ihren Auftrag nicht
# und antwortete, wie es ihr gefiel). Die deny-Liste bleibt, wie sie ist.
exec claude -p \
    --model "$MODEL" \
    --tools "" \
    --strict-mcp-config \
    --output-format json \
    --no-session-persistence \
    --append-system-prompt-file "$AGENT_DIR/CLAUDE.md"
