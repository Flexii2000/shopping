# Einkaufsliste

Eine Liste für zwei Personen, Rezepte, die als Ganzes drauf kommen, und
Dinge, die sich von selbst drauf setzen. Ein Spring-Boot-Dienst unter
`fherrmann.com/shopping-list` mit Weboberfläche; die iOS-Clients sind der
Einkaufsliste-Tab in **Fokus** und die eigene App **Einkaufsliste**
(`~/Server-Projects/cockpit-ios`, gemeinsamer Code in `Shopping/`).

## Zugang: eigene Token, je Person einer

Der Dienst liegt **nicht** hinter dem Privat-Gate von fherrmann.com. Der
Privat-Cookie öffnet alles Private auf einmal; hier soll eine zweite Person
genau die Einkaufsliste bedienen können — und sonst nichts. Deshalb prüft der
Dienst eigene Token aus `/etc/shopping.env`:

```
SHOPPING_TOKENS=felix:<token>,joana:<token>
```

Der Name vor dem Token steht an den Einträgen („abgehakt von joana"). Die
Token erzeugt `deploy/setup-shopping.sh` beim ersten Lauf und gibt die Links
aus. Zwei Wege, ihn mitzuschicken:

| Client | Wie |
|---|---|
| Browser | einmal `https://fherrmann.com/shopping-list/setup?token=…` öffnen — setzt das Cookie `shopping_token` (HttpOnly, Secure, SameSite=Lax, 400 Tage) und leitet auf die Seite um |
| App | `Authorization: Bearer <token>` an jeder Anfrage |

Ohne gültigen Token antwortet `/api/**` mit **401** und einem Satz Klartext;
die Seite selbst (`index.html`, `app.js`, …) und `/setup` sind frei — sonst
könnte man den Link gar nicht öffnen. Die Seite zeigt dann „Kein Zugang".

## Was es kann

- **Liste.** Einträge mit optionaler Menge („500 g") und Notiz. Abhaken ist
  **idempotent**: der erste Haken zählt, ein zweiter ändert nichts — zwei
  Handys im selben Supermarkt kommen sich nicht in die Quere.
- **Abgehaktes bleibt bis Mitternacht** (Berlin) durchgestrichen sichtbar und
  fällt dann aus dem Brett. In der Datei bleibt es 90 Tage
  (`shopping.history-days`), ein nächtlicher Aufräumer löscht Älteres.
  „Abgehakte entfernen" (`clear-checked`) löscht sie sofort und wirklich.
- **Rezepte** (bis November 2026 „Gerichte"; im Brett, in der Datei und in den
  Pfaden heißen sie weiter `dishes`). Ein Rezept hat Zutaten (Name + Menge) und
  auf Wunsch Portionen, Minuten, Schritte, Nährwerte je Portion und das Gewicht
  einer Portion. Ein Gericht von früher ist ein Rezept ohne diese Felder. Namen
  sind eindeutig (Groß/Klein und Leerraum zählen nicht) – gleichnamige von
  früher bleiben und lassen sich weiter bearbeiten.
- **Auf die Liste** legt je Zutat einen Eintrag an, mit dem Rezeptnamen als
  Notiz; das Rezept zu löschen lässt Einträge auf der Liste stehen. Neu: **für
  N Portionen** – die Mengen werden von den Portionen des Rezepts umgerechnet –
  und **ohne** einzelne Zutaten (`skip`). Zutaten mit `pantry` (Salz, Öl – „hat
  man da") sind in den Oberflächen nicht vorgewählt.
- **Umrechnen** (`QuantityParser.scale`): kaufmännisch gerundet, je nach
  Einheit – `g`/`ml` ab 10 auf ganze, darunter auf eine Nachkommastelle;
  `kg`/`l`/`cl`/`Pfund` auf zwei Nachkommastellen; alles andere (Stk, Dose, EL …)
  auf halbe; nie auf 0. Keine Umrechnung zwischen Einheiten, kein Aufrunden auf
  ganze Packungen – auf der Liste werden Mengen zusammengezählt, und die Summe
  soll stimmen. Was sich nicht lesen lässt („eine Handvoll", „2-3 Stk", „ca.
  200 g"), bleibt unverändert und kommt mit `scaled: false` (die Oberflächen
  zeigen es blasser). Die App rechnet nach derselben Tabelle
  (`ShoppingQuantity.scale` in cockpit-ios); die gemeinsamen Beispiele stehen in
  `src/test/resources/scale-vectors.json`.
- **Rezept-Chat.** „Ich will heute etwas mit Pasta und Feta kochen" – eine
  Claude-Code-Session auf dem Server antwortet mit ein paar Sätzen und einem
  Rezeptvorschlag; „mach es vegetarisch" ändert ihn. Gefällt er, speichert ein
  Tipp ihn unter Rezepte (`source: "chat"`). Je Person ein Gespräch, je Person
  schaltbar, 40 Nachrichten am Tag. Mehr dazu unten („Rezept-Chat").
- **Rezepte in Healthy.** Jedes Rezept mit Nährwerten ist im Kalorienzähler von
  Healthy ein Gericht – bei **allen**, die die Einkaufsliste benutzen und Healthy
  haben, und zwar **schon beim Speichern** (seit 11.11.: anlegen, ändern, aus dem
  Chat übernehmen), nicht erst beim Kochen. Es heißt dort `Rezept: <Name>` (wie die
  Kantine `Kochwerk: …`): der Kalorienzähler ordnet über den Namen zu, und ein von
  Hand gepflegtes Gericht gleichen Namens soll nicht überschrieben werden. Werte je
  100 g = je Portion × 100 ÷ Gewicht der Portion, die Portion wird die Vorgabe beim
  Eintragen. „Kochen" fragt noch einmal und zeigt das Ergebnis je Person. Ohne
  Nährwerte gibt es dort nichts. Mehr dazu unten („Rezepte in Healthy").
- **Derselbe Name wird ein Eintrag** — Groß-/Kleinschreibung und Leerraum
  zählen nicht. Mengen gleicher Einheit werden addiert („500 g" + „500 g" =
  „1000 g"), verschiedene stehen nebeneinander („500 g + 1 Pck"). Ein
  abgehakter Eintrag zählt nicht mehr als „steht schon drauf". Gilt für
  Eingaben, Rezepte und Regeln (eine Regel hängt sich an den vorhandenen
  Eintrag, Abhaken schiebt sie weiter).
- **Sortiert nach Kategorien** — eine Liste, keine Abschnitte, in der
  Reihenfolge eines Supermarkt-Rundgangs: Obst & Gemüse, Backwaren,
  Fleisch & Wurst, Fisch & Meeresfrüchte, Milchprodukte, Konserven, Vorrat & Trockenwaren,
  Gewürze & Saucen, Snacks & Süßes, Getränke, Tiefkühl, Drogerie, Haushalt,
  Sonstiges. Die Kategorie **rät der Dienst** aus dem Namen
  (`Categorizer`): erst, was jemand für diesen Namen von Hand gewählt hat
  (`learned` in der Datei), dann ein Wörterbuch mit über 800 deutschen
  Artikelnamen (`categories.txt`, längster Treffer gewinnt — „Kokosmilch"
  ist Vorrat, nicht Milchprodukt; „TK-…" ist immer Tiefkühl), dann Endungen
  („…käse", „…saft"), sonst Sonstiges. Mengen im Namen („2 Zwiebeln",
  „Milch 1l") und Plural stören nicht.
- **Regelmäßig** („Klopapier alle 14 Tage"): ein Lauf (stündlich, beim Start
  und direkt nach dem Anlegen) setzt je fälliger Regel **einen** Eintrag,
  solange keiner von ihr offen ist. **Der Rhythmus zählt ab dem Abhaken**:
  der Haken schiebt `nextAt` auf heute + `everyDays`. Wer den Eintrag löscht
  statt ihn zu kaufen, bekommt ihn beim nächsten Lauf wieder. Ohne `nextAt`
  beim Anlegen ist die Regel ab heute fällig — der Eintrag erscheint sofort.

## REST-API

Alles unter `/shopping-list/api`, mit Token (sonst 401). **Jede Antwort, die
Liste, Rezepte oder Regeln ändert, ist das ganze Brett** (`Board`); die Clients
setzen nichts zusammen. Drei Antworten sind kein Brett, weil sie nichts daran
ändern: die umgerechneten Zutaten (`Scaled`), das Ergebnis von „Kochen" (`Cook`)
und der Rezept-Chat (`Chat`).

| Methode | Pfad | Was |
|---|---|---|
| GET | `/api/board` | alles: `me`, sichtbare Einträge, Rezepte (`dishes`), Regeln, `categories` |
| POST | `/api/items` | `{name, quantity?, note?, category?, id?}` → 201; mit `category` wird die Wahl für den Namen gelernt; `id` siehe unten |
| PUT | `/api/items/{id}` | `{name, quantity?, note?, category?}` — fehlende Felder heißen: keine mehr; ohne `category` wird neu geraten |
| DELETE | `/api/items/{id}` | löschen |
| POST | `/api/items/{id}/check` | abhaken (idempotent; bei einer Regel: `nextAt` weiterschieben) |
| DELETE | `/api/items/{id}/check` | Haken zurück (bei offenem Eintrag: nichts) |
| POST | `/api/items/clear-checked` | alle abgehakten löschen; mit `{ids:[…]}` nur diese, sofern noch abgehakt |
| POST | `/api/dishes` | `{name, ingredients:[{name, quantity?, pantry?}], portions?, minutes?, steps?, nutrition?, gramsPerPortion?}` → 201; leere Zutatenzeilen fallen weg; 409, wenn ein Rezept schon so heißt |
| PUT | `/api/dishes/{id}` | gleicher Rumpf. **Fehlt ein Feld, bleibt es** (ältere Apps schicken nur Name und Zutaten); der leere Wert löscht: `steps: []`, `portions: 0`, `minutes: 0`, `gramsPerPortion: 0`, `nutrition: {}`. Nur `ingredients` ersetzt die alten ganz; fehlt bei einer Zutat `pantry`, behält die gleichnamige ihr Kennzeichen |
| DELETE | `/api/dishes/{id}` | löschen, Einträge bleiben |
| POST | `/api/dishes/{id}/add` | je Zutat ein Eintrag → 201 (400 ohne Zutaten). Ohne Rumpf alles wie immer. Optional `{portions, skip:[Namen], items:[{name, id}]}`: `portions` rechnet um (400, wenn das Rezept keine Portionen hat), `skip` lässt Zutaten weg, `items` trägt die Kennungen der App |
| GET | `/api/dishes/{id}/scaled?portions=N` | → `Scaled`: die Zutaten für N Portionen (ohne `portions` die des Rezepts); 400 ohne Portionen am Rezept. Ändert nichts |
| POST | `/api/dishes/{id}/cook` | „Kochen" → 200 `Cook`: das Rezept noch einmal als Gericht in den Kalorienzähler (dort steht es seit dem Speichern), je Person mit Ergebnis. Kein Rumpf. Auch bei einem Ausfall 200 – kochen kann man trotzdem |
| GET | `/api/chat` | → `Chat`: das eigene Gespräch – und die Nachfrage, solange `job.status` `running` ist (alle 2 s) |
| POST | `/api/chat/messages` | `{text, id?}` → 202 `Chat` mit `job.status: "running"`. 400 leer oder über 1.000 Zeichen, 403 nicht freigeschaltet, 409 solange eine Antwort läuft, 429 Tageslimit, 503 kein Agent. Dieselbe `id` noch einmal hängt nichts an (siehe unten) |
| DELETE | `/api/chat` | „Neu" → 200 leeres `Chat`; eine Antwort, die noch unterwegs war, wird verworfen |
| POST | `/api/chat/drafts/{messageId}/save` | den Entwurf einer Antwort als Rezept speichern → 201 `Board`; ein zweiter Aufruf legt nichts mehr an; 409, wenn ein Rezept schon so heißt (der Entwurf bleibt speicherbar) |
| POST | `/api/recurring` | `{name, quantity?, everyDays, nextAt?}` → 201, `nextAt` als `yyyy-MM-dd`, ohne = heute |
| PUT | `/api/recurring/{id}` | gleicher Rumpf; ohne `nextAt` bleibt der bisherige Termin |
| DELETE | `/api/recurring/{id}` | löschen, ein offener Eintrag bleibt |
| GET | `/setup?token=…` | Cookie setzen, 302 auf die Seite; falscher Token → 403 |

```
Board    me, items[], dishes[], recurring[], categories[]
Category key, label, emoji, symbol, color     (in Sortierreihenfolge; color als #RRGGBB)
Item     id, name, quantity | null, note | null, category, addedAt, addedBy,
         checkedAt | null, checkedBy | null, dishId | null, ruleId | null
Dish     id, name, ingredients[] {name, quantity | null, pantry}, createdAt,
         portions | null, minutes | null, steps[], nutrition | null {kcal, proteinG,
         carbsG, fatG}, gramsPerPortion | null, source | null, createdBy | null
Rule     id, name, quantity | null, everyDays, nextAt, createdAt

Scaled   dishId, portions, basePortions, ingredients[] {name, quantity | null, pantry, scaled}
Cook     dishId, reason | null, healthy[] {person, status, message | null}
Chat     available, messages[] {id, role, text, at, draft | null, savedDishId | null},
         job | null {status, elapsedSeconds?, error?}
Draft    name, portions | null, minutes | null, ingredients[] {name, quantity | null, pantry},
         steps[], nutrition | null, gramsPerPortion | null        (wie der Rumpf eines Rezepts)
```

`nutrition` gilt **je Portion**, alle vier Werte oder keiner, und braucht
`gramsPerPortion`; je 100 g muss es in den Kalorienzähler passen (höchstens
1.000 kcal und 100 g je Makro – sonst 400). Grenzen: `portions` 1–99, `minutes`
1–1.440, `gramsPerPortion` 1–5.000, höchstens 40 Schritte zu 500 Zeichen und 60
Zutaten. `source` ist `"chat"` bei einem gespeicherten Entwurf, `createdBy` der
Name hinter dem Token (beides setzt der Dienst). `scaled: false` heißt: die
Menge ließ sich nicht lesen und steht da, wie sie gespeichert ist.

`Cook.reason` ist `null`, wenn der Kalorienzähler gefragt wurde, sonst
`"no-nutrition"` (das Rezept hat keine Nährwerte) oder `"not-configured"`
(kein Dienst-Token). `healthy[].status`: `created`, `updated`, `unchanged`,
`rejected` (der Kalorienzähler nimmt das Gericht nicht, `message` sagt warum)
oder `failed` (nicht erreichbar – dann bei allen). **Wer die Einkaufsliste
benutzt, aber kein Healthy hat, steht nicht in der Liste.**

`Chat.available`: die Person ist freigeschaltet und der Agent eingerichtet –
sonst zeigen die Clients den Chat gar nicht. `role` ist `user` oder
`assistant`; `job.status` ist `running` (mit `elapsedSeconds`) oder `failed`
(mit `error`, ein deutscher Satz).

Zeitpunkte als ISO-8601 mit `Z`, Datumsangaben als `yyyy-MM-dd`. `addedBy`
ist der Name hinter dem Token oder `regel`. `category` ist nie `null`
(Rückfall `other`). Einträge: offene zuerst — nach Kategorie in
Rundgang-Reihenfolge, darin älteste oben —, dann die heute abgehakten
(zuletzt abgehakte oben). Fehler kommen als
Klartext (`Ein Eintrag braucht einen Namen.`).

**Kennungen der App** (seit 2026-10-12): Im Laden fehlt oft das Netz. Die Apps
zeigen Hinzufügen, Abhaken, Ändern und Löschen deshalb sofort und schicken später.
Ein neuer Eintrag braucht dafür eine Kennung, bevor der Dienst ihn kennt: die App
vergibt sie (`id` beim Anlegen, je Zutat bei `dishes/{id}/add`; kleingeschriebene
UUID, sonst 400 `Die Kennung ist keine UUID.`).

- **Dieselbe Kennung zweimal legt nichts an** — nachgesendet würden aus 2 l Milch
  sonst 4 l.
- **Geht der Eintrag in einem offenen gleichen Namens auf**, merkt sich der Dienst
  in `clientIds`, wohin; Abhaken, Ändern und Löschen mit der Kennung der App treffen
  dann diesen Eintrag (auch den der anderen Person — gleicher Name, ein Eintrag).
- **Gelöscht bleibt gelöscht:** ein spät nachgesendetes Anlegen holt nichts zurück.
- `clear-checked` mit `ids` löscht nur, was die App abgehakt sah — ein Haken, den die
  andere Person inzwischen gesetzt hat, bleibt stehen.
- Alte Kennungen räumt der nächtliche Lauf nach `shopping.history-days` weg.

Ohne `id` (Weboberfläche, ältere Apps) bleibt alles wie vorher. Alle Schreiber laufen
nacheinander (`synchronized`), auch der Stundenlauf der Regeln.

## Rezept-Chat

Jede Antwort ist **eine Claude-Code-Session auf dem Server**, gestartet wie die
Agenten des Kalorienzählers: `sudo -n -u flexii ~/services/recipe-agent/run-agent.sh`,
der Auftrag über die Standardeingabe, die Antwort als JSON. Die Session hat
**kein einziges Werkzeug** (`--tools ""`, dazu die deny-Liste), lädt keinen
MCP-Server und speichert nichts (`--no-session-persistence`). Vorlage des
Verzeichnisses: `deploy/recipe-agent/` (`CLAUDE.md`, `run-agent.sh`,
`.claude/settings.json`); es liegt auf dem Server **neben** dem Repo, und
`update-shopping.sh` frischt es bei jedem Deploy auf.

- **Ablauf.** `POST /api/chat/messages` antwortet sofort mit 202; die Antwort
  entsteht im Hintergrund (ein Arbeitsthread für alle), die Clients fragen
  `GET /api/chat` alle 2 s ab. Eine offene Anfrage über eine halbe Minute
  scheiterte an nginx (60 s) ohne verwertbaren Fehler – dasselbe Muster wie die
  Schnellerfassung des Kalorienzählers.
- **Der Auftrag** an die Session: die Namen der gespeicherten Rezepte
  (`<rezepte>`), die letzten 12 Nachrichten (`<verlauf>`), der jüngste Entwurf
  als JSON (`<entwurf>`), Ziele und Essen der fragenden Person (`<ernaehrung>`,
  siehe unten) und die neue Nachricht (`<anfrage>`). Alles darin ist
  Zitat; Text, der wie eine dieser Klammern aussieht, wird entschärft
  (`<anfrage>` → `[anfrage]`). Keine Namen von Personen.
- **Ziele und Essen je Person** (seit 10.11., Felix: „Wie passt das zu meinen
  Zielen?"): vor jeder Antwort holt der Dienst
  `POST {FOOD_URL}/api/internal/recipe-context {"person": <wer schreibt>}` mit
  demselben Dienst-Token wie beim Kochen – Tagesziele samt Eiweiß-Erhalt, heute
  gegessen und offen, die 14 Tage davor, das Mittel der letzten sieben
  vollständigen Tage, die häufigsten Einträge (90 Tage), vegan. **Immer nur die
  Person, die schreibt**: das Gespräch ist je Person, jede Session startet neu
  ohne Gedächtnis, und der Kalorienzähler gibt je Anfrage genau eine Person
  heraus (ohne ihren Namen). Joanas Chat sieht nie Felix' Daten und umgekehrt.
  Ohne Healthy (404) oder wenn der Kalorienzähler ausfällt, fehlt der Block –
  die Antwort kommt trotzdem.
- **Die Antwort** ist ein JSON-Objekt `{reply, recipe | null}`; im Rezept kommen
  Zutaten als `amount` + `unit`, wobei `unit` aus der Tabelle von
  `QuantityParser` stammen muss (`RecipeAgentContractTest` hält die Liste in
  der `CLAUDE.md` und die im Code gleich). Gelesen wird nachsichtig: Umschlag
  oder blanke Antwort, Codeblock, Mengen als Text („1,5", „1/2"), Schritte als
  ein Text, eine unbekannte Einheit wird Text (sichtbar, nicht umrechenbar);
  Überlanges wird gekürzt, unbrauchbare Nährwerte fallen weg, das Rezept bleibt.
- **Dieselbe Kennung noch einmal** (`id`, kleingeschriebene UUID der App) hängt
  nichts an. Steht die Nachricht noch ohne Antwort da und läuft nichts mehr –
  die Antwort scheiterte, oder der Dienst lief neu (Aufträge liegen nur im
  Speicher) –, fordert sie nur die Antwort neu an. Die Clients bieten dann
  „Erneut senden" an.
- **Was es kostet, hält in Grenzen:** die Freigabe je Person
  (`SHOPPING_RECIPE_CHAT`, leer = niemand), ein Arbeitsthread, eine offene Frage
  je Person, 40 Nachrichten je Person und Tag
  (`shopping.recipe-agent.daily-limit`), 1.000 Zeichen je Nachricht, 120 s je
  Session. Alles läuft auf Felix' Claude-Login – läuft der ab, antwortet der
  Chat mit „Claude konnte nicht antworten.".
- **Eingerichtet?** Der Dienst probiert beim Start einmal
  `run-agent.sh --version` (Log: „Rezept-Agent bereit …"). Nur dann meldet
  `GET /api/chat` `available: true`. Das ist die Probe unter den echten
  Bedingungen der Unit – `NoNewPrivileges` sperrte sudo dort, ohne dass eine
  Probe von außen es merkte.

## Rezepte in Healthy

**Wann:** nach jedem Speichern eines Rezepts (`POST /api/dishes`, `PUT /api/dishes/{id}`,
`POST /api/chat/drafts/{id}/save`) im Hintergrund – das Speichern wartet nicht darauf
und scheitert nie daran. Dazu einmal nach jedem Start für alle Rezepte (holt nach,
was beim Speichern nicht ankam; ist der Kalorienzähler nicht da, bricht der Abgleich
nach dem ersten Versuch ab) und bei „Kochen" (`POST /api/dishes/{id}/cook`, mit
Antwort je Person). Das Log sagt je Rezept „Rezept in Healthy: …“ und je Start
„Rezepte mit Healthy abgeglichen: …“.

**Wie:** ein Aufruf beim Kalorienzähler:
`POST {FOOD_URL}/api/internal/recipe-dishes` mit
`{"persons":[alle Namen aus SHOPPING_TOKENS],"dish":{"name":"Rezept: …","kcal","proteinG","carbsG","fatG","portionG"},"previousName":"Rezept: …"}`
(Werte je 100 g; `previousName` nur nach dem Umbenennen) und
`Authorization: Bearer <SHOPPING_HEALTHY_TOKEN>`. Der
Kalorienzähler entscheidet je Name, ob es die Person bei ihm gibt, liest,
vergleicht und schreibt (`created` / `updated` / `unchanged` / `not-linked` /
`rejected`). Der Endpunkt ist nur lokal erreichbar (nginx sperrt
`/api/internal/`).

**Ein eigener Dienst-Token, keine persönlichen.** Die Einkaufsliste hängt ohne
Privat-Gate am Netz. Lägen hier die Healthy-Token von Felix und Joana, öffnete
ein Fehler in diesem Dienst ihnen Kalorienzähler, Weight Tracker und coHabit.
Dieser Token kann genau eines: ein Gericht anlegen. Derselbe Wert steht als
`FOOD_RECIPE_TOKEN` in `/etc/food.env`; `deploy/setup-recipes.sh` erzeugt ihn.

Was man wissen sollte: Der Kalorienzähler ordnet über den Namen zu. Wird ein
Rezept umbenannt, benennt er das Gericht um (`previousName`) – solange es den neuen
Namen dort noch nicht gibt; ging das Umbenennen beim Speichern verloren, legt der
Abgleich beim Start ein zweites an. Gelöscht wird dort nie etwas, auch nicht mit dem
Rezept. Wer das Gericht `Rezept: …` in Healthy von Hand ändert, verliert die Änderung
beim nächsten Speichern des Rezepts (oder beim nächsten Start) an dessen Werte.

## Daten

`data/shopping.json` — Liste, Rezepte (`dishes`), Regeln, gelernte Kategorien, Kennungen der Apps (`clientIds`, fehlt in älteren Dateien). Geschrieben wird erst
daneben, dann umbenannt. ⚠️ Auf dem Server nie überschreiben.

`data/recipe-chat.json` — je Person ein Gespräch des Rezept-Chats (höchstens 30
Nachrichten, nach 30 Tagen Stille weg) und die Zähler des Tageslimits. Bewusst
eine eigene Datei: ein kaputter Verlauf darf die Liste nicht mitreißen (er
fängt dann leer an). Modus `rw-------`.

**Neue Felder in den Dateien sind immer Wrapper-Typen** (`Integer`, `Boolean`,
`Double`): Jackson 3 scheitert an einem fehlenden primitiven Feld, und die
Datei von gestern wäre nach dem Deploy nicht mehr lesbar. Der Test
`dieDateiVonVorDenRezeptenLiestSichUnveraendert` liest eine Datei, die der
Dienst am 10.11.2026 geschrieben hat (`src/test/resources/shopping-before-recipes.json`).

## Betrieb

`shopping.service` (User `shopping`, `/opt/shopping`) auf `127.0.0.1:48220`,
nginx `location /shopping-list/` unter `fherrmann.com` **ohne** Privat-Gate.

```bash
# einmalig
ssh HeimServerRemote 'git clone git@github.com:Flexii2000/shopping.git ~/services/shopping'
ssh -t HeimServerRemote '~/services/shopping/deploy/setup-shopping.sh'   # gibt die Setup-Links aus
# später (ohne Passwort, auch für Agenten: update-service.sh, SERVER-CONTEXT.md)
ssh HeimServerLocal '~/services/shopping/deploy/update-shopping.sh'
# einmalig für Rezept-Chat und "Kochen" - NACH einem Update auf einen Stand mit Rezepten
ssh -t HeimServerRemote '~/services/shopping/deploy/setup-recipes.sh'
```

Namen ändern oder ein Token tauschen: `/etc/shopping.env`, dann
`sudo systemctl restart shopping`.

**`deploy/setup-recipes.sh`** (idempotent, braucht einmal Felix' sudo-Passwort)
richtet ein:

| Was | Wo |
|---|---|
| Agent-Verzeichnis | `~/services/recipe-agent/` (gehört flexii, sonst schreibt niemand), als vertrauenswürdig in `~/.claude.json` |
| sudo-Regel | `/etc/sudoers.d/25-recipe-agent`: `shopping` darf genau `run-agent.sh` als `flexii` starten |
| Drop-in | `/etc/systemd/system/shopping.service.d/10-agent.conf` mit `NoNewPrivileges=false` – die Unit im Repo bleibt, wie sie ist |
| `/etc/shopping.env` | `SHOPPING_RECIPE_AGENT_COMMAND` (leer, wenn die sudo-Probe scheitert); `SHOPPING_RECIPE_CHAT` (alle Namen aus `SHOPPING_TOKENS`) und `FOOD_URL` nur, wenn sie fehlen; `SHOPPING_HEALTHY_TOKEN` |
| `/etc/food.env` | `FOOD_RECIPE_TOKEN`, falls es noch keinen gibt (dann startet auch `food` neu) |

Am Ende prüft es: Unit aktiv und ohne `NoNewPrivileges`, der Dienstnutzer darf
nur den eigenen Agenten starten (nichts sonst als flexii, keinen der anderen
Agenten), 401 ohne Token, das Brett mit Token, `available: true` im Chat, der
Deploy ohne Passwort geht weiter, der Kalorienzähler nimmt den Dienst-Token an.
Secrets stehen dabei nie auf einer Kommandozeile und nie in der Ausgabe.

Weitere Einstellungen in `/etc/shopping.env` (danach `sudo systemctl restart shopping`):
`RECIPE_AGENT_MODEL` (Vorgabe `sonnet`; sudo reicht nur diese eine Variable
durch). Chat abschalten: `SHOPPING_RECIPE_AGENT_COMMAND` leeren – alles andere
an den Rezepten geht weiter.

## Bauen

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home ./gradlew test
./gradlew bootRun   # lokal: http://127.0.0.1:48220/shopping-list/setup?token=changeme-felix
```

Lokal gibt es keinen Rezept-Chat (kein Kommando, niemand freigeschaltet) und
„Kochen" meldet `not-configured`. Zum Ausprobieren ein Skript, das statt der
Session antwortet (es muss `--version` mit 0 beenden und sonst die Antwort
ausgeben, wie in `ClaudeRecipeAgentTest`):

```bash
./gradlew bootRun --args='--shopping.recipe-agent.command=/pfad/zur/attrappe.sh --shopping.recipe-agent.people=*'
```

Java 25, Spring Boot 4, keine Datenbank — wie `todo` und `habits`.
