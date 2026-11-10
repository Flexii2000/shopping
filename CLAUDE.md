# Arbeitsregeln für `shopping`

Ein Spring-Boot-Dienst für die Einkaufsliste von Felix und Joana:
eine Liste, Rezepte mit Zutaten (und Chat, der welche vorschlägt), Regeln für
Dinge, die sich von selbst auf die Liste setzen. Läuft als
`fherrmann.com/shopping-list` — **nicht** im privaten Bereich, sondern mit
**eigenen Token je Person**. Clients: die Weboberfläche hier, der
Einkaufsliste-Tab in Fokus und die App Einkaufsliste
(`~/Server-Projects/cockpit-ios`, `Shopping/`).

## Vor dem ersten Handgriff

1. `README.md` lesen — Zugang, Regeln und API stehen dort.
2. `../SERVER-CONTEXT.md` für Deploy, nginx, Port.
3. Die Apps lesen `Board` so, wie er hier serialisiert wird. Wer ein Feld
   umbenennt, zieht `../cockpit-ios/Shared/ShoppingModels.swift` mit.
4. Rezept-Chat und „Kochen" hängen am Server: Agent-Verzeichnis, sudo-Regel,
   Drop-in, Dienst-Token (`deploy/setup-recipes.sh`, README „Betrieb").

## Die Regeln, die nicht offensichtlich sind

- **Eigene Token, kein Privat-Cookie.** `SHOPPING_TOKENS=name:token,…` in
  `/etc/shopping.env`. Der Name ist der Principal und steht an den Einträgen.
  Bearer-Header (App) und Cookie `shopping_token` (Browser, via `/setup`)
  sind gleichwertig. **Tokens nie ins Repo** — auch nicht in Tests mit
  echten Werten oder in Kommentaren.
- **Abgehaktes bleibt bis Mitternacht sichtbar**, dann nur in der Datei,
  nach 90 Tagen weg. `clear-checked` löscht sofort.
- **Abhaken ist idempotent** — der erste Haken zählt.
- **Regeln zählen ab dem Kauf**: der Haken schiebt `nextAt`, nicht der
  Kalender. Eine Regel setzt nie einen zweiten offenen Eintrag.
- **Die Liste ist nach Kategorien sortiert**, nicht gruppiert. Die
  Kategorie rät `Categorizer` (gelernt → Wörterbuch `categories.txt` →
  Endungen → Sonstiges). Neue Artikel, die falsch landen: Eintrag ins
  Wörterbuch, Fall in `CategorizerTest`. Von Hand gewählte Kategorien
  landen in `learned` und schlagen alles.
- **Jede Antwort, die etwas ändert, ist das ganze Brett.** Keine
  Teilantworten. Drei Ausnahmen, die nichts an Liste und Rezepten ändern:
  `GET dishes/{id}/scaled` (umgerechnete Zutaten), `POST dishes/{id}/cook`
  (Ergebnis je Person) und der Rezept-Chat (`/api/chat…`, antwortet mit dem
  Gespräch; nur das Speichern eines Entwurfs liefert wieder das Brett).
- **Rezepte heißen im Code, im Brett und in der Datei weiter `Dish`/`dishes`** —
  ältere Apps lesen genau das. Nur die Beschriftungen sagen „Rezept".
- **Neue Felder in gespeicherten Records sind Wrapper-Typen** (`Integer`,
  `Boolean`, `Double`), nie `int`/`boolean`/`double`: Jackson 3 scheitert an
  einem fehlenden primitiven Feld, und `/opt/shopping/data/shopping.json` wäre
  nach dem Deploy unlesbar — die Liste weg. Dazu ein Fall in
  `ShoppingRepositoryTest` gegen `shopping-before-recipes.json`. **Keine
  Hilfsmethoden `isX()`/`getX()` an Records**: Jackson schriebe sie als Feld.
- **PUT eines Rezepts: fehlt ein Feld, bleibt es.** Ältere Apps schicken nur
  Name und Zutaten. Der leere Wert löscht (`steps: []`, `portions: 0`,
  `minutes: 0`, `gramsPerPortion: 0`, `nutrition: {}`); nur `ingredients`
  ersetzt wie immer ganz, `pantry` erbt die gleichnamige Zutat.
- **Die Rundung beim Umrechnen steht zweimal**: `QuantityParser.scale` hier und
  `ShoppingQuantity.scale` in cockpit-ios (für die Anzeige ohne Netz). Beide
  laufen gegen `src/test/resources/scale-vectors.json` — wer die Tabelle
  ändert, ändert die Datei und zieht die App mit.
- **Der Vertrag mit dem Rezept-Agenten steht in zwei Dateien**:
  `deploy/recipe-agent/CLAUDE.md` (wonach die Session arbeitet) und
  `ClaudeRecipeAgent` (was der Dienst liest). `RecipeAgentContractTest` hält
  Einheiten und Beispiel beieinander. Der Agent bekommt **nie** ein Werkzeug,
  und `run-agent.sh` reicht keine Argumente durch.
- **Kochen benutzt einen eigenen Dienst-Token** (`SHOPPING_HEALTHY_TOKEN`),
  keine persönlichen Healthy-Token — dieser Dienst hängt ohne Privat-Gate am
  Netz. Token nie loggen, nie in Fehlermeldungen.
- **Die App darf Kennungen vergeben** (`id` beim Anlegen, je Zutat bei
  `dishes/{id}/add`): dieselbe zweimal legt nichts an, eine in einem gleichnamigen
  Eintrag aufgegangene trifft diesen (`clientIds`), `clear-checked` mit `ids` löscht
  nur die genannten. Ohne Kennung alles wie vorher. Schreiber sind `synchronized`.

## Bauen und prüfen

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home ./gradlew test
./gradlew bootRun   # lokal: http://127.0.0.1:48220/shopping-list/setup?token=changeme-felix
node --check src/main/resources/static/app.js   # die Weboberfläche hat keinen Build und keine Tests
```

**Nie behaupten, etwas baue, ohne `./gradlew test` gelaufen zu haben.** Die
Tests starten nie eine echte Claude-Session — an ihrer Stelle antwortet ein
Skript (`ClaudeRecipeAgentTest`).

## Konventionen

Wie bei `todo`: Bezeichner englisch, Kommentare deutsch (das Warum); Fehler
als Klartext; committen **und** pushen — der Server baut aus dem Repo.
Commits als Felix Herrmann, ohne Claude-Zeilen. Die Weboberfläche ist
Vanilla-JS ohne Framework (`static/app.js`); sie zeichnet nur, was der Dienst
liefert. ⚠️ `/opt/shopping/data/shopping.json` nie überschreiben.
