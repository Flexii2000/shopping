# Arbeitsregeln für `shopping`

Ein Spring-Boot-Dienst für die Einkaufsliste von Felix und seiner Freundin:
eine Liste, Gerichte mit Zutaten, Regeln für Dinge, die sich von selbst auf
die Liste setzen. Läuft als `fherrmann.com/shopping-list` — **nicht** im
privaten Bereich, sondern mit **eigenen Token je Person**. Clients: die
Weboberfläche hier, der Einkaufsliste-Tab in Healthy und die App Einkauf
(`~/Server-Projects/cockpit-ios`).

## Vor dem ersten Handgriff

1. `README.md` lesen — Zugang, Regeln und API stehen dort.
2. `../SERVER-CONTEXT.md` für Deploy, nginx, Port.
3. Die Apps lesen `Board` so, wie er hier serialisiert wird. Wer ein Feld
   umbenennt, zieht `../cockpit-ios/Shared/ShoppingModels.swift` mit.

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
- **Jede Antwort ist das ganze Brett.** Keine Teilantworten.

## Bauen und prüfen

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home ./gradlew test
./gradlew bootRun   # lokal: http://127.0.0.1:48220/shopping-list/setup?token=changeme-felix
```

**Nie behaupten, etwas baue, ohne `./gradlew test` gelaufen zu haben.**

## Konventionen

Wie bei `todo`: Bezeichner englisch, Kommentare deutsch (das Warum); Fehler
als Klartext; committen **und** pushen — der Server baut aus dem Repo.
Commits als Felix Herrmann, ohne Claude-Zeilen. Die Weboberfläche ist
Vanilla-JS ohne Framework (`static/app.js`); sie zeichnet nur, was der Dienst
liefert. ⚠️ `/opt/shopping/data/shopping.json` nie überschreiben.
