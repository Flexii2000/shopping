# Einkaufsliste

Eine Liste für zwei Personen, Gerichte, die als Ganzes drauf kommen, und
Dinge, die sich von selbst drauf setzen. Ein Spring-Boot-Dienst unter
`fherrmann.com/shopping-list` mit Weboberfläche; die iOS-Clients sind der
Einkaufsliste-Tab in **Healthy** und die eigene App **Einkauf**
(`~/Server-Projects/cockpit-ios`).

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
- **Gerichte** mit Zutaten (Name + Menge). „Auf die Liste" legt je Zutat einen
  Eintrag an, mit dem Gerichtnamen als Notiz. Doppelte sind erlaubt. Das
  Gericht zu löschen lässt Einträge auf der Liste stehen. Bewusst nicht mit
  dem Kalorienzähler verknüpft — dort haben Gerichte Nährwerte je 100 g, hier
  Einkaufsmengen.
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

Alles unter `/shopping-list/api`, mit Token (sonst 401). **Jede Antwort ist
das ganze Brett** (`Board`); die Clients setzen nichts zusammen.

| Methode | Pfad | Was |
|---|---|---|
| GET | `/api/board` | alles: `me`, sichtbare Einträge, Gerichte, Regeln, `categories` |
| POST | `/api/items` | `{name, quantity?, note?, category?}` → 201; mit `category` wird die Wahl für den Namen gelernt |
| PUT | `/api/items/{id}` | `{name, quantity?, note?, category?}` — fehlende Felder heißen: keine mehr; ohne `category` wird neu geraten |
| DELETE | `/api/items/{id}` | löschen |
| POST | `/api/items/{id}/check` | abhaken (idempotent; bei einer Regel: `nextAt` weiterschieben) |
| DELETE | `/api/items/{id}/check` | Haken zurück (bei offenem Eintrag: nichts) |
| POST | `/api/items/clear-checked` | alle abgehakten löschen |
| POST | `/api/dishes` | `{name, ingredients:[{name, quantity?}]}` → 201; leere Zutatenzeilen fallen weg |
| PUT | `/api/dishes/{id}` | gleicher Rumpf, ersetzt die Zutaten ganz |
| DELETE | `/api/dishes/{id}` | löschen, Einträge bleiben |
| POST | `/api/dishes/{id}/add` | je Zutat ein Eintrag → 201 (400 ohne Zutaten) |
| POST | `/api/recurring` | `{name, quantity?, everyDays, nextAt?}` → 201, `nextAt` als `yyyy-MM-dd`, ohne = heute |
| PUT | `/api/recurring/{id}` | gleicher Rumpf; ohne `nextAt` bleibt der bisherige Termin |
| DELETE | `/api/recurring/{id}` | löschen, ein offener Eintrag bleibt |
| GET | `/setup?token=…` | Cookie setzen, 302 auf die Seite; falscher Token → 403 |

```
Board    me, items[], dishes[], recurring[], categories[]
Category key, label, emoji, symbol, color     (in Sortierreihenfolge; color als #RRGGBB)
Item     id, name, quantity | null, note | null, category, addedAt, addedBy,
         checkedAt | null, checkedBy | null, dishId | null, ruleId | null
Dish     id, name, ingredients[] {name, quantity | null}, createdAt
Rule     id, name, quantity | null, everyDays, nextAt, createdAt
```

Zeitpunkte als ISO-8601 mit `Z`, Datumsangaben als `yyyy-MM-dd`. `addedBy`
ist der Name hinter dem Token oder `regel`. `category` ist nie `null`
(Rückfall `other`). Einträge: offene zuerst — nach Kategorie in
Rundgang-Reihenfolge, darin älteste oben —, dann die heute abgehakten
(zuletzt abgehakte oben). Fehler kommen als
Klartext (`Ein Eintrag braucht einen Namen.`).

## Daten

`data/shopping.json` — Liste, Gerichte, Regeln, gelernte Kategorien. Geschrieben wird erst
daneben, dann umbenannt. ⚠️ Auf dem Server nie überschreiben.

## Betrieb

`shopping.service` (User `shopping`, `/opt/shopping`) auf `127.0.0.1:48220`,
nginx `location /shopping-list/` unter `fherrmann.com` **ohne** Privat-Gate.

```bash
# einmalig
ssh HeimServerRemote 'git clone git@github.com:Flexii2000/shopping.git ~/services/shopping'
ssh -t HeimServerRemote '~/services/shopping/deploy/setup-shopping.sh'   # gibt die Setup-Links aus
# später
ssh -t HeimServerRemote '~/services/shopping/deploy/update-shopping.sh'
```

Namen ändern oder ein Token tauschen: `/etc/shopping.env`, dann
`sudo systemctl restart shopping`.

## Bauen

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home ./gradlew test
./gradlew bootRun   # lokal: http://127.0.0.1:48220/shopping-list/setup?token=changeme-felix
```

Java 25, Spring Boot 4, keine Datenbank — wie `todo` und `habits`.
