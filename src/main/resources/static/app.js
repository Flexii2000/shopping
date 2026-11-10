// Die Einkaufsliste: eine Liste fuer zwei Personen, Rezepte als Ganzes drauf,
// Regeln, die sich selbst drauf setzen. Jede Antwort des Dienstes, die etwas
// aendert, ist das ganze Brett - hier wird nur gezeichnet, nie gerechnet.
//
// Vier Sichten, gewaehlt ueber den #-Teil der Adresse: das Brett (ohne), ein
// Rezept (#rezept/<id>), der Rezept-Chat (#chat) und die Kochansicht
// (#kochen/<id>/<portionen>). Chat und Kochansicht zeichnen sich nur selbst neu -
// nie, weil im Hintergrund das Brett neu kam.
(function () {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const banner = $("banner");
  const main = $("main");
  const viewBox = $("view");
  const noaccess = $("noaccess");
  let board = null;
  // Welches Formular gerade offen ist: {kind:'item'|'dish'|'rule', id} - so
  // ueberlebt es das Neuzeichnen nach einer Antwort.
  let editing = null;
  // Welche Sicht offen ist: {name:'board'|'recipe'|'chat'|'cook', id, portions}.
  let view = { name: "board" };
  // Das offene Rezept: {id, portions, off:Set der abgewaehlten Zutaten, scaled}.
  let detail = null;
  // Der Rezept-Chat, wie der Dienst ihn zuletzt geliefert hat, und sein Nachfragen.
  let chat = null;
  let chatPoll = null;
  let chatSince = 0;
  let chatOpenDraft = null;
  let chatDrawn = "";
  // Die Kochansicht: {id, portions, scaled, result, ticks}, dazu die Bildschirmsperre.
  let cook = null;
  let wakeLock = null;

  async function call(method, path, body) {
    const res = await fetch("api/" + path, {
      method,
      headers: body ? { "Content-Type": "application/json" } : {},
      body: body ? JSON.stringify(body) : undefined,
      credentials: "same-origin",
    });
    if (res.status === 401) {
      const err = new Error("Kein Zugang");
      err.noAccess = true;
      throw err;
    }
    if (!res.ok) {
      // Fehler kommen als Klartext ("Ein Eintrag braucht einen Namen.").
      throw new Error((await res.text()) || "HTTP " + res.status);
    }
    return res.json();
  }

  function fail(err) {
    if (err.noAccess) {
      main.hidden = true;
      viewBox.hidden = true;
      noaccess.hidden = false;
      return;
    }
    banner.textContent = err.message;
    banner.hidden = false;
  }

  // true, wenn der Dienst die Aenderung angenommen hat - sonst kann der Aufrufer
  // zuruecknehmen, was der Browser schon angezeigt hat (Haken, geleerte Felder).
  async function run(method, path, body) {
    try {
      banner.hidden = true;
      render(await call(method, path, body));
      return true;
    } catch (err) {
      fail(err);
      return false;
    }
  }

  const load = () => run("GET", "board");

  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  function button(cls, text, onClick, title) {
    const b = el("button", cls, text);
    b.type = "button";
    if (title) b.title = title;
    b.addEventListener("click", onClick);
    return b;
  }

  function input(cls, placeholder, value, attrs) {
    const i = el("input", cls);
    i.placeholder = placeholder || "";
    i.value = value == null ? "" : value;
    Object.assign(i, attrs || {});
    return i;
  }

  // Heute in Ortszeit als yyyy-mm-dd - der Dienst rechnet in Berlin, und
  // wer die Liste benutzt, ist dort.
  function today() {
    const d = new Date();
    return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-"
      + String(d.getDate()).padStart(2, "0");
  }

  function fmtDate(iso) {
    const [y, m, d] = iso.split("-").map(Number);
    return String(d).padStart(2, "0") + "." + String(m).padStart(2, "0") + "." + String(y).slice(2);
  }

  // "heute", "morgen", "in 3 Tagen", weiter weg das Datum - wie bei To-Do.
  function whenLabel(iso) {
    const [y, m, d] = iso.split("-").map(Number);
    const [ty, tm, td] = today().split("-").map(Number);
    const days = Math.round((Date.UTC(y, m - 1, d) - Date.UTC(ty, tm - 1, td)) / 86400000);
    if (days <= 0) return "heute";
    if (days === 1) return "morgen";
    if (days <= 7) return "in " + days + " Tagen";
    return "am " + fmtDate(iso);
  }

  function fmtTime(iso) {
    return new Date(iso).toLocaleTimeString("de-DE", { hour: "2-digit", minute: "2-digit" });
  }

  // MARK: - Die Liste

  function category(key) {
    return board.categories.find((c) => c.key === key) || board.categories[board.categories.length - 1];
  }

  // MARK: - Mengen: Zahl und Einheit

  // Der Dienst speichert die Menge als Text ("500 g", "2 Stk"). Hier wird sie
  // in Zahl und Einheit auseinandergelesen und genauso zurueckgeschrieben; eine
  // Zahl ohne Einheit heisst Stueck. Was sich nicht lesen laesst ("eine
  // Handvoll"), bleibt Text. Dieselben Regeln wie ShoppingQuantity in der App.
  const UNITS = ["Stk", "g", "kg", "ml", "l", "Pck"];
  const UNIT_NAMES = { "": "Stk", stk: "Stk", "stück": "Stk", stueck: "Stk", st: "Stk", x: "Stk", stck: "Stk",
    g: "g", gr: "g", gramm: "g", kg: "kg", kilo: "kg", kilogramm: "kg", ml: "ml", milliliter: "ml",
    l: "l", liter: "l", ltr: "l", pck: "Pck", pk: "Pck", pkg: "Pck", pack: "Pck", packung: "Pck" };

  function parseQuantity(text) {
    const m = /^(\d+(?:[.,]\d+)?)\s*([A-Za-zÄÖÜäöü.]*)$/.exec((text || "").trim());
    if (!m) return null;
    const unit = UNIT_NAMES[m[2].toLowerCase().replace(/\./g, "")];
    return unit ? { amount: m[1], unit } : null;
  }

  function composeQuantity(field, unit) {
    const text = (field || "").trim();
    if (!text) return null;
    const parsed = parseQuantity(text);
    if (!parsed) return text;
    return parsed.amount + " " + (/[A-Za-zÄÖÜäöü]/.test(text) ? parsed.unit : unit);
  }

  function unitSelect(selected) {
    const select = el("select", "unit");
    select.title = "Einheit";
    for (const u of UNITS) {
      const opt = el("option", null, u);
      opt.value = u;
      opt.selected = u === selected;
      select.append(opt);
    }
    return select;
  }

  // Ein Mengenfeld: schmale Zahl plus Einheit. `read()` liefert den Text fuer
  // den Dienst.
  function quantityField(existing) {
    const parsed = parseQuantity(existing);
    const num = input("qty", "Menge", parsed ? parsed.amount : (existing || ""), { maxLength: 40, inputMode: "decimal" });
    const unit = unitSelect(parsed ? parsed.unit : "Stk");
    return { num, unit, read: () => composeQuantity(num.value, unit.value) };
  }

  function itemRow(item) {
    const li = el("li", "item" + (item.checkedAt ? " checked" : ""));
    const box = el("input");
    box.type = "checkbox";
    box.checked = !!item.checkedAt;
    box.title = item.checkedAt ? "Wieder öffnen" : "Abhaken";
    box.addEventListener("change", async () => {
      const wanted = box.checked;
      // Der Browser hat den Haken schon gesetzt; nimmt der Dienst ihn nicht an,
      // stuende sonst ein Haken da, den es nicht gibt.
      if (!(await run(wanted ? "POST" : "DELETE", "items/" + item.id + "/check"))) box.checked = !wanted;
    });
    // Das Icon der Kategorie vor dem Namen - die Liste ist danach sortiert,
    // Abschnitte gibt es keine: eine Liste, nur in Rundgang-Reihenfolge.
    const cat = category(item.category);
    const icon = el("span", "icon", cat.emoji);
    icon.title = cat.label;
    // Die Farbe der Kategorie als Kreis hinter dem Emoji - so sind die
    // Gruppen auch ohne Abschnitte auf einen Blick zu unterscheiden.
    if (cat.color) {
      icon.style.background = cat.color + "2E";
      icon.style.boxShadow = "inset 0 0 0 1.5px " + cat.color + "80";
    }
    const title = el("span", "title", item.name);
    title.prepend(icon);
    if (item.quantity) title.append(el("span", "qty", item.quantity));
    const meta = [];
    if (item.note) meta.push(item.note);
    if (item.checkedAt) meta.push("abgehakt von " + item.checkedBy + " um " + fmtTime(item.checkedAt));
    else if (item.addedBy === "regel") meta.push("von selbst, nach Regel");
    else if (item.addedBy !== board.me) meta.push("von " + item.addedBy);
    if (meta.length) title.append(el("span", "meta", meta.join(" · ")));
    title.title = "Ändern";
    title.addEventListener("click", () => {
      editing = editing && editing.kind === "item" && editing.id === item.id ? null : { kind: "item", id: item.id };
      render(board);
    });
    const actions = el("span", "actions");
    actions.append(button("x del", "×", () => {
      if (confirm("„" + item.name + "“ von der Liste nehmen?")) run("DELETE", "items/" + item.id);
    }, "Löschen"));
    li.append(box, title, actions);
    return li;
  }

  function itemEditor(item) {
    const box = el("div", "editor");
    const row = el("div", "row");
    const name = input("grow", "Name", item.name, { maxLength: 200 });
    const qty = quantityField(item.quantity);
    const note = input("grow", "Notiz", item.note, { maxLength: 200 });
    row.append(name, qty.num, qty.unit, note);
    // Die Kategorie von Hand setzen - der Dienst merkt sich das fuer den
    // Namen. Leer lassen heisst: neu raten lassen.
    const catRow = el("div", "row");
    const select = el("select");
    for (const c of board.categories) {
      const opt = el("option", null, c.emoji + " " + c.label);
      opt.value = c.key;
      opt.selected = c.key === item.category;
      select.append(opt);
    }
    let touched = false;
    select.addEventListener("change", () => { touched = true; });
    catRow.append(el("span", "lbl", "Kategorie"), select);
    const buttons = el("div", "row");
    buttons.append(
      button("mini", "Speichern", () => {
        editing = null;
        run("PUT", "items/" + item.id, { name: name.value, quantity: qty.read(), note: note.value,
          category: touched ? select.value : null });
      }),
      button("mini quiet", "Abbrechen", () => { editing = null; render(board); }));
    box.append(row, catRow, buttons);
    return box;
  }

  function renderItems() {
    const list = $("items");
    list.replaceChildren();
    let open = 0, checked = 0;
    for (const item of board.items) {
      if (item.checkedAt) checked++; else open++;
      list.append(itemRow(item));
      if (editing && editing.kind === "item" && editing.id === item.id) list.append(itemEditor(item));
    }
    $("open-count").textContent = open === 1 ? "1 offen" : open + " offen";
    $("items-empty").hidden = board.items.length > 0;
    $("clear").hidden = checked === 0;
  }

  // MARK: - Rezepte

  function dishById(id) {
    return board ? board.dishes.find((d) => d.id === id) : null;
  }

  // Ein Gericht von frueher: nur Name und Zutaten. Das kommt weiter mit einem
  // Tipp auf die Liste; alles mit Portionen, Schritten oder Vorrat oeffnet sich.
  function isPlain(dish) {
    return dish.portions == null && !dish.minutes && !(dish.steps && dish.steps.length) && !dish.nutrition
      && !dish.ingredients.some((i) => i.pantry);
  }

  function portionsLabel(n) {
    return n === 1 ? "1 Portion" : n + " Portionen";
  }

  function dishMeta(dish) {
    const parts = [];
    if (dish.portions) parts.push(portionsLabel(dish.portions));
    if (dish.minutes) parts.push(dish.minutes + " Min.");
    if (!parts.length) {
      const n = dish.ingredients.length;
      parts.push(n === 1 ? "1 Zutat" : n + " Zutaten");
    }
    return parts.join(" · ");
  }

  function dishTile(dish) {
    const tile = el("div", "tile");
    const name = el("a", "name", dish.name);
    name.href = "#rezept/" + dish.id;
    tile.append(name, el("div", "sub", dishMeta(dish)));
    const row = el("div", "row");
    if (isPlain(dish)) {
      row.append(button("mini", "Auf die Liste", () => run("POST", "dishes/" + dish.id + "/add"), "Alle Zutaten auf die Liste"));
    } else {
      row.append(button("mini", "Öffnen", () => { location.hash = "#rezept/" + dish.id; }));
    }
    row.append(
      button("x", "✎", () => { editing = { kind: "dish", id: dish.id }; render(board); }, "Bearbeiten"),
      button("x del", "×", () => deleteDish(dish), "Löschen"));
    tile.append(row);
    return tile;
  }

  async function deleteDish(dish) {
    if (!confirm("Rezept „" + dish.name + "“ löschen? Einträge auf der Liste bleiben.")) return;
    if (await run("DELETE", "dishes/" + dish.id) && view.name !== "board") location.hash = "";
  }

  // Eine Zahl aus einem Feld; leer heisst 0 - und 0 heisst fuer den Dienst
  // "keine Angabe" bzw. beim Aendern "loeschen".
  function numberOf(field) {
    const value = Number(String(field.value).trim().replace(",", "."));
    return Number.isFinite(value) && value > 0 ? value : 0;
  }

  function numberField(placeholder, value, title) {
    const field = input("num", placeholder, value == null ? "" : String(value).replace(".", ","), { inputMode: "decimal", maxLength: 7 });
    field.title = title || placeholder;
    return field;
  }

  // Anlegen und Aendern in einem Formular: Name, Portionen und Minuten, dann
  // Zutatenzeilen (Name + Menge + Vorrat), eine leere Zeile immer unten - wer sie
  // befuellt, bekommt die naechste. Darunter die Schritte, je Zeile einer, und die
  // Naehrwerte je Portion.
  function dishEditor(dish) {
    const box = el("div", "editor top");
    const head = el("div", "row");
    const name = input("grow", "Name des Rezepts", dish ? dish.name : "", { maxLength: 200 });
    head.append(el("span", "lbl", dish ? "Rezept" : "Neu"), name);
    box.append(head);

    const meta = el("div", "row");
    const portions = numberField("2", dish ? dish.portions : null, "Portionen");
    const minutes = numberField("30", dish ? dish.minutes : null, "Minuten");
    meta.append(el("span", "lbl", "Portionen"), portions, el("span", "lbl short", "Minuten"), minutes);
    box.append(meta);

    const rows = el("div");
    box.append(rows);
    const lines = [];
    function addLine(ing) {
      const row = el("div", "row");
      const n = input("grow", "Zutat", ing ? ing.name : "", { maxLength: 200 });
      const q = quantityField(ing ? ing.quantity : "");
      const pantry = el("input");
      pantry.type = "checkbox";
      pantry.checked = !!(ing && ing.pantry);
      const pantryLabel = el("label", "check");
      pantryLabel.append(pantry, "Vorrat");
      const rm = button("x del", "×", () => { rows.removeChild(row); lines.splice(lines.indexOf(line), 1); ensureEmpty(); }, "Zutat entfernen");
      const line = { n, q, pantry };
      lines.push(line);
      row.append(n, q.num, q.unit, pantryLabel, rm);
      rows.append(row);
      n.addEventListener("input", ensureEmpty);
    }
    function ensureEmpty() {
      if (!lines.length || lines[lines.length - 1].n.value.trim()) addLine(null);
    }
    for (const ing of (dish ? dish.ingredients : [])) addLine(ing);
    ensureEmpty();

    const steps = el("textarea", "steps");
    steps.placeholder = "Schritte";
    steps.rows = Math.max(3, dish && dish.steps ? dish.steps.length + 1 : 3);
    steps.value = dish && dish.steps ? dish.steps.join("\n") : "";
    box.append(steps);

    const nutrition = dish ? dish.nutrition : null;
    const nrow = el("div", "row");
    const kcal = numberField("kcal", nutrition ? nutrition.kcal : null, "kcal je Portion");
    const protein = numberField("Eiweiß", nutrition ? nutrition.proteinG : null, "Eiweiß je Portion (g)");
    const carbs = numberField("KH", nutrition ? nutrition.carbsG : null, "Kohlenhydrate je Portion (g)");
    const fat = numberField("Fett", nutrition ? nutrition.fatG : null, "Fett je Portion (g)");
    const grams = numberField("g", dish ? dish.gramsPerPortion : null, "Gewicht einer Portion (g)");
    nrow.append(el("span", "lbl", "je Portion"), kcal, protein, carbs, fat, el("span", "lbl short", "Gewicht"), grams);
    box.append(nrow);

    const buttons = el("div", "row");
    buttons.append(
      button("mini", "Speichern", async () => {
        const ingredients = lines
          .filter((l) => l.n.value.trim())
          .map((l) => ({ name: l.n.value, quantity: l.q.read(), pantry: l.pantry.checked }));
        const fields = [kcal, protein, carbs, fat];
        // Alle vier leer heisst: keine Naehrwerte ({}). Sonst gehen sie, wie sie
        // dastehen - fehlt einer, sagt der Dienst es.
        const anyNutrition = fields.some((f) => f.value.trim());
        const body = {
          name: name.value,
          ingredients,
          portions: numberOf(portions),
          minutes: numberOf(minutes),
          steps: steps.value.split("\n").map((line) => line.trim()).filter(Boolean),
          gramsPerPortion: numberOf(grams),
          nutrition: anyNutrition
            ? { kcal: valueOrNull(kcal), proteinG: valueOrNull(protein), carbsG: valueOrNull(carbs), fatG: valueOrNull(fat) }
            : {},
        };
        // Erst schliessen, wenn der Dienst es angenommen hat: lehnt er ab ("Nährwerte
        // brauchen ..."), bleibt das Formular mit allem stehen, was getippt war.
        try {
          banner.hidden = true;
          const next = dish ? await call("PUT", "dishes/" + dish.id, body) : await call("POST", "dishes", body);
          editing = null;
          render(next);
        } catch (err) {
          fail(err);
        }
      }),
      button("mini quiet", "Abbrechen", () => { editing = null; render(board); }));
    box.append(buttons);
    if (!dish) setTimeout(() => name.focus(), 0);
    return box;
  }

  // Ein Naehrwert, wie er dasteht: leer bleibt leer (der Dienst verlangt dann alle vier), 0 ist 0.
  function valueOrNull(field) {
    const text = String(field.value).trim().replace(",", ".");
    if (!text) return null;
    const value = Number(text);
    return Number.isFinite(value) ? value : null;
  }

  function renderDishes() {
    const tiles = $("dishes");
    tiles.replaceChildren();
    for (const dish of board.dishes) tiles.append(dishTile(dish));
    $("dish-count").textContent = board.dishes.length ? board.dishes.length : "";
    const editor = $("dish-editor");
    editor.replaceChildren();
    if (editing && editing.kind === "dish") {
      const dish = editing.id ? board.dishes.find((d) => d.id === editing.id) : null;
      if (editing.id && !dish) editing = null;
      else {
        editor.append(dishEditor(dish));
        // Aus der Rezeptansicht geoeffnet: das Formular steht unter den Kacheln,
        // also einmal dorthin.
        if (editing.reveal) {
          editing.reveal = false;
          setTimeout(() => editor.scrollIntoView({ block: "center" }), 0);
        }
      }
    }
  }

  // MARK: - Ein Rezept

  function backLink(text, hash) {
    const a = el("a", "back", "‹ " + text);
    a.href = hash || "#";
    return a;
  }

  function nutritionLine(n) {
    const g = (value) => String(Math.round(value)).replace(".", ",") + " g";
    return Math.round(n.kcal) + " kcal · Eiweiß " + g(n.proteinG) + " · KH " + g(n.carbsG) + " · Fett " + g(n.fatG);
  }

  // Die Zutaten in der gewuenschten Portionenzahl. Umgerechnet hat der Dienst
  // (dishes/{id}/scaled); ohne Portionen am Rezept stehen sie da, wie sie sind.
  function ingredientsFor(dish, scaled) {
    if (scaled && scaled.dishId === dish.id) return scaled.ingredients;
    return dish.ingredients.map((i) => ({ name: i.name, quantity: i.quantity, pantry: !!i.pantry, scaled: true }));
  }

  async function loadScaled(dish, portions) {
    if (!dish.portions) return null;
    return call("GET", "dishes/" + dish.id + "/scaled?portions=" + portions);
  }

  function openRecipe() {
    const dish = dishById(view.id);
    if (!dish) { location.hash = ""; return; }
    if (!detail || detail.id !== dish.id) {
      detail = { id: dish.id, portions: dish.portions || null,
        off: new Set(dish.ingredients.filter((i) => i.pantry).map((i) => i.name)), scaled: null };
    }
  }

  async function setPortions(dish, portions) {
    if (portions < 1 || portions > 99 || !detail) return;
    try {
      banner.hidden = true;
      const scaled = await loadScaled(dish, portions);
      if (!detail || detail.id !== dish.id) return;
      detail.portions = portions;
      detail.scaled = scaled;
      if (view.name === "recipe") renderRecipe();
    } catch (err) {
      fail(err);
    }
  }

  function renderRecipe() {
    const dish = dishById(view.id);
    if (!dish) { location.hash = ""; return; }
    if (!detail || detail.id !== dish.id) openRecipe();
    viewBox.className = "card view";
    viewBox.replaceChildren();

    const top = el("div", "viewhead");
    const tools = el("span", "tools");
    tools.append(
      button("x", "✎", () => { editing = { kind: "dish", id: dish.id, reveal: true }; location.hash = ""; }, "Bearbeiten"),
      button("x del", "×", () => deleteDish(dish), "Löschen"));
    top.append(backLink("Rezepte"), tools);
    viewBox.append(top, el("h2", "title", dish.name));

    const meta = el("div", "metaline");
    if (dish.minutes) meta.append(el("span", null, dish.minutes + " Min."));
    if (dish.portions) {
      const stepper = el("span", "stepper");
      stepper.append(
        button("x", "−", () => setPortions(dish, detail.portions - 1), "Weniger"),
        el("span", "n", portionsLabel(detail.portions)),
        button("x", "+", () => setPortions(dish, detail.portions + 1), "Mehr"));
      meta.append(stepper);
    }
    if (meta.childNodes.length) viewBox.append(meta);

    const list = el("ul", "checklist");
    for (const ing of ingredientsFor(dish, detail.scaled)) {
      const li = el("li");
      const label = el("label");
      const box = el("input");
      box.type = "checkbox";
      box.checked = !detail.off.has(ing.name);
      box.addEventListener("change", () => { if (box.checked) detail.off.delete(ing.name); else detail.off.add(ing.name); });
      label.append(box, el("span", "name", ing.name));
      if (ing.quantity) label.append(el("span", "qty" + (ing.scaled ? "" : " dim"), ing.quantity));
      li.append(label);
      list.append(li);
    }
    viewBox.append(list);

    if (dish.steps && dish.steps.length) {
      const steps = el("ol", "steps");
      for (const step of dish.steps) steps.append(el("li", null, step));
      viewBox.append(steps);
    }
    if (dish.nutrition) viewBox.append(el("div", "nutrition", nutritionLine(dish.nutrition)));

    const actions = el("div", "row actions");
    if (dish.ingredients.length) {
      actions.append(button("mini", "Auf die Liste", async () => {
        const body = { skip: Array.from(detail.off) };
        if (dish.portions) body.portions = detail.portions;
        if (await run("POST", "dishes/" + dish.id + "/add", body)) location.hash = "";
      }));
    }
    actions.append(button("mini", "Kochen", () => {
      location.hash = "#kochen/" + dish.id + (dish.portions ? "/" + detail.portions : "");
    }));
    viewBox.append(actions);
  }

  // MARK: - Kochen

  function ticksKey(id) {
    return "kochen:" + id;
  }

  function loadTicks(id) {
    try {
      return JSON.parse(sessionStorage.getItem(ticksKey(id))) || { ingredients: {}, steps: {} };
    } catch (err) {
      return { ingredients: {}, steps: {} };
    }
  }

  function saveTicks() {
    try {
      sessionStorage.setItem(ticksKey(cook.id), JSON.stringify(cook.ticks));
    } catch (err) {
      // Ohne Speicher gelten die Haken, solange die Seite offen ist.
    }
  }

  // Der Bildschirm soll beim Kochen nicht ausgehen. Wo der Browser das nicht kann,
  // bleibt es beim Geraet.
  async function holdScreen() {
    if (!("wakeLock" in navigator) || wakeLock || view.name !== "cook" || document.hidden) return;
    try {
      wakeLock = await navigator.wakeLock.request("screen");
      wakeLock.addEventListener("release", () => { wakeLock = null; });
    } catch (err) {
      wakeLock = null;
    }
  }

  function releaseScreen() {
    if (wakeLock) wakeLock.release().catch(() => {});
    wakeLock = null;
  }

  async function sendCook() {
    if (!cook) return;
    const id = cook.id;
    cook.result = null;
    cook.cooking = true;
    drawCook();
    let result = null;
    try {
      result = await call("POST", "dishes/" + id + "/cook");
    } catch (err) {
      if (err.noAccess) { fail(err); return; }
      // Das Netz ist weg: kochen geht trotzdem, nur Healthy weiss noch nichts davon.
      result = { dishId: id, reason: null, healthy: [], offline: true };
    }
    if (!cook || cook.id !== id) return;
    cook.result = result;
    cook.cooking = false;
    drawCook();
  }

  async function openCook() {
    const dish = dishById(view.id);
    if (!dish) { location.hash = ""; return; }
    const portions = dish.portions ? (view.portions >= 1 && view.portions <= 99 ? view.portions : dish.portions) : null;
    cook = { id: dish.id, portions, scaled: null, result: null, cooking: false, ticks: loadTicks(dish.id) };
    drawCook();
    holdScreen();
    // "Kochen" legt das Rezept als Gericht in Healthy an - einmal je Oeffnen.
    sendCook();
    if (portions && portions !== dish.portions) {
      try {
        const scaled = await loadScaled(dish, portions);
        if (cook && cook.id === dish.id) { cook.scaled = scaled; drawCook(); }
      } catch (err) {
        fail(err);
      }
    }
  }

  function tickRow(text, extra, checked, onChange) {
    const li = el("li", checked ? "done" : "");
    const label = el("label");
    const box = el("input");
    box.type = "checkbox";
    box.checked = checked;
    box.addEventListener("change", () => { li.className = box.checked ? "done" : ""; onChange(box.checked); });
    label.append(box, el("span", "name", text));
    if (extra) label.append(extra);
    li.append(label);
    return li;
  }

  function drawCook() {
    if (view.name !== "cook" || !cook) return;
    const dish = dishById(cook.id);
    if (!dish) { location.hash = ""; return; }
    viewBox.className = "card view cook";
    viewBox.replaceChildren();
    const top = el("div", "viewhead");
    top.append(backLink("Rezept", "#rezept/" + dish.id));
    viewBox.append(top, el("h2", "title", dish.name));

    const meta = el("div", "metaline");
    if (cook.portions) meta.append(el("span", null, portionsLabel(cook.portions)));
    if (dish.minutes) meta.append(el("span", null, dish.minutes + " Min."));
    // Bei wem das Gericht jetzt in Healthy steht - ein Zeichen je Person.
    const result = cook.result;
    if (result && result.healthy.length) {
      const marks = el("span", "marks");
      let failed = false;
      for (const person of result.healthy) {
        const ok = person.status === "created" || person.status === "updated" || person.status === "unchanged";
        failed = failed || !ok;
        const mark = el("span", "mark" + (ok ? "" : " bad"), (ok ? "✓ " : "✕ ") + person.person);
        mark.title = person.message || "Healthy";
        marks.append(mark);
      }
      if (failed) marks.append(button("x", "↻", sendCook, "Noch einmal an Healthy schicken"));
      meta.append(marks);
    } else if (result && result.offline) {
      const marks = el("span", "marks");
      const mark = el("span", "mark bad", "✕ Healthy");
      marks.append(mark, button("x", "↻", sendCook, "Noch einmal an Healthy schicken"));
      meta.append(marks);
    }
    if (meta.childNodes.length) viewBox.append(meta);

    const list = el("ul", "checklist");
    for (const ing of ingredientsFor(dish, cook.scaled)) {
      const qty = ing.quantity ? el("span", "qty" + (ing.scaled ? "" : " dim"), ing.quantity) : null;
      list.append(tickRow(ing.name, qty, !!cook.ticks.ingredients[ing.name], (on) => {
        cook.ticks.ingredients[ing.name] = on;
        saveTicks();
      }));
    }
    viewBox.append(list);

    if (dish.steps && dish.steps.length) {
      const steps = el("ol", "checklist steps");
      dish.steps.forEach((step, index) => {
        steps.append(tickRow(step, null, !!cook.ticks.steps[index], (on) => {
          cook.ticks.steps[index] = on;
          saveTicks();
        }));
      });
      viewBox.append(steps);
    }
  }

  // MARK: - Rezept-Chat

  function newId() {
    return window.crypto && crypto.randomUUID ? crypto.randomUUID() : null;
  }

  function chatRunning() {
    return !!(chat && chat.job && chat.job.status === "running");
  }

  function stopChatPoll() {
    if (chatPoll) clearTimeout(chatPoll);
    chatPoll = null;
  }

  // Solange eine Antwort entsteht, alle zwei Sekunden nachfragen. Nach
  // zweieinhalb Minuten ist eine Session beim Dienst laengst beendet - laeuft dann
  // noch etwas, wartet die Frage hinter der einer anderen Person, und es reicht,
  // seltener nachzusehen.
  function scheduleChatPoll() {
    stopChatPoll();
    if (view.name !== "chat" || !chatRunning()) return;
    chatPoll = setTimeout(refreshChat, Date.now() - chatSince > 150000 ? 10000 : 2000);
  }

  async function refreshChat() {
    try {
      const next = await call("GET", "chat");
      setChat(next);
    } catch (err) {
      fail(err);
    }
  }

  function setChat(next) {
    const wasRunning = chatRunning();
    chat = next;
    $("open-chat").hidden = !chat.available;
    if (chatRunning() && !wasRunning) chatSince = Date.now();
    if (view.name === "chat") {
      drawChat();
      scheduleChatPoll();
    }
  }

  async function sendChat(text, id) {
    try {
      banner.hidden = true;
      chatSince = Date.now();
      setChat(await call("POST", "chat/messages", { text, id }));
      return true;
    } catch (err) {
      fail(err);
      return false;
    }
  }

  function draftCard(message) {
    const draft = message.draft;
    const card = el("div", "draft");
    card.append(el("div", "name", draft.name));
    const meta = [];
    if (draft.portions) meta.push(portionsLabel(draft.portions));
    if (draft.minutes) meta.push(draft.minutes + " Min.");
    if (draft.nutrition) meta.push(Math.round(draft.nutrition.kcal) + " kcal");
    if (meta.length) card.append(el("div", "sub", meta.join(" · ")));
    if (chatOpenDraft === message.id) {
      const list = el("ul", "checklist plain");
      for (const ing of draft.ingredients) {
        const li = el("li");
        li.append(el("span", "name", ing.name));
        if (ing.quantity) li.append(el("span", "qty", ing.quantity));
        list.append(li);
      }
      card.append(list);
      if (draft.steps.length) {
        const steps = el("ol", "steps");
        for (const step of draft.steps) steps.append(el("li", null, step));
        card.append(steps);
      }
      if (draft.nutrition) card.append(el("div", "nutrition", nutritionLine(draft.nutrition)));
    }
    const row = el("div", "row");
    row.append(button("mini quiet", chatOpenDraft === message.id ? "Zuklappen" : "Ansehen", () => {
      chatOpenDraft = chatOpenDraft === message.id ? null : message.id;
      drawChat();
    }));
    if (message.savedDishId && dishById(message.savedDishId)) {
      const open = el("a", "saved", "Gespeichert ›");
      open.href = "#rezept/" + message.savedDishId;
      row.append(open);
    } else if (message.savedDishId) {
      row.append(el("span", "saved", "Gespeichert"));
    } else {
      row.append(button("mini", "Speichern", async () => {
        try {
          banner.hidden = true;
          // Die Antwort ist das Brett mit dem neuen Rezept; der Vermerk am Entwurf
          // kommt mit dem Gespraech.
          board = await call("POST", "chat/drafts/" + message.id + "/save");
          await refreshChat();
        } catch (err) {
          fail(err);
        }
      }));
    }
    card.append(row);
    return card;
  }

  function drawChat() {
    if (view.name !== "chat" || !chat) return;
    // Was gerade im Feld steht, ueberlebt das Neuzeichnen.
    const typedField = viewBox.querySelector("#chat-text");
    const typed = typedField ? typedField.value : "";
    const hadFocus = typedField && document.activeElement === typedField;
    viewBox.className = "card view chat";
    viewBox.replaceChildren();

    const top = el("div", "viewhead");
    const tools = el("span", "tools");
    if (chat.messages.length) {
      tools.append(button("x", "Neu", async () => {
        try {
          banner.hidden = true;
          chatOpenDraft = null;
          setChat(await call("DELETE", "chat"));
        } catch (err) {
          fail(err);
        }
      }, "Neues Gespräch"));
    }
    top.append(backLink("Rezepte"), tools);
    viewBox.append(top);

    const thread = el("div", "thread");
    for (const message of chat.messages) {
      const own = message.role === "user";
      const row = el("div", "msg " + (own ? "own" : "theirs"));
      if (message.text) row.append(el("div", "bubble", message.text));
      if (message.draft) row.append(draftCard(message));
      thread.append(row);
    }
    const running = chatRunning();
    const last = chat.messages[chat.messages.length - 1];
    if (running) {
      const row = el("div", "msg theirs");
      row.append(el("div", "bubble wait", "…"));
      thread.append(row);
    } else if (last && last.role === "user") {
      // Die eigene Nachricht steht ohne Antwort da: gescheitert, oder der Dienst
      // lief zwischendurch neu. Dieselbe Kennung fordert nur die Antwort neu an.
      const row = el("div", "msg theirs");
      if (chat.job && chat.job.status === "failed") row.append(el("div", "bubble bad", chat.job.error));
      row.append(button("mini quiet", "Erneut senden", () => sendChat(last.text, last.id)));
      thread.append(row);
    }
    viewBox.append(thread);

    const form = el("form", "add");
    const field = input("", "Nachricht", typed, { maxLength: 1000, id: "chat-text" });
    field.autocomplete = "off";
    const send = el("button", null, "↑");
    send.type = "submit";
    send.title = "Senden";
    send.disabled = running || !chat.available;
    form.append(field, send);
    form.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const text = field.value.trim();
      if (!text || chatRunning()) return;
      field.value = "";
      // Scheitert das Absenden, steht wieder da, was getippt war.
      if (!(await sendChat(text, newId()))) {
        const again = viewBox.querySelector("#chat-text");
        if (again && !again.value) again.value = text;
      }
    });
    viewBox.append(form);
    if (hadFocus || !chat.messages.length) field.focus();
    // Ans Ende nur, wenn etwas dazukam - wer einen aelteren Entwurf aufklappt,
    // soll dort bleiben.
    const drawn = chat.messages.length + ":" + running;
    if (drawn !== chatDrawn) window.scrollTo(0, document.body.scrollHeight);
    chatDrawn = drawn;
  }

  async function openChat() {
    chatDrawn = "";
    viewBox.className = "card view chat";
    viewBox.replaceChildren(backLink("Rezepte"));
    if (chat) drawChat();
    await refreshChat();
  }

  // MARK: - Sichten

  function parseHash() {
    const parts = location.hash.replace(/^#/, "").split("/");
    if (parts[0] === "rezept" && parts[1]) return { name: "recipe", id: parts[1] };
    if (parts[0] === "chat") return { name: "chat" };
    if (parts[0] === "kochen" && parts[1]) return { name: "cook", id: parts[1], portions: Number(parts[2]) || null };
    return { name: "board" };
  }

  // Zeichnet, was zur Sicht gehoert und sich aus dem Brett ergibt. Chat und
  // Kochansicht fehlen hier mit Absicht: sie zeichnen sich nur selbst.
  function show() {
    const onBoard = view.name === "board";
    main.hidden = !onBoard;
    viewBox.hidden = onBoard;
    if (onBoard) {
      renderItems();
      renderDishes();
      renderRules();
    } else if (view.name === "recipe") {
      renderRecipe();
    }
  }

  function enterView() {
    stopChatPoll();
    releaseScreen();
    view = parseHash();
    if (view.name !== "recipe") detail = null;
    if (view.name !== "cook") cook = null;
    if (!board) return;
    banner.hidden = true;
    if (view.name === "recipe") openRecipe();
    show();
    if (view.name === "chat") openChat();
    else if (view.name === "cook") openCook();
    if (view.name !== "board") window.scrollTo(0, 0);
  }

  // MARK: - Regeln

  function ruleRow(rule) {
    const li = el("li", "rule");
    const name = el("span", "name", rule.name);
    if (rule.quantity) name.append(el("span", "qty", rule.quantity));
    const due = rule.nextAt <= today();
    li.append(name,
      el("span", "when" + (due ? " due" : ""), "alle " + rule.everyDays + " Tage · " + whenLabel(rule.nextAt)));
    const actions = el("span", "actions");
    actions.append(
      button("x", "✎", () => { editing = { kind: "rule", id: rule.id }; render(board); }, "Bearbeiten"),
      button("x del", "×", () => {
        if (confirm("Regel „" + rule.name + "“ löschen?")) run("DELETE", "recurring/" + rule.id);
      }, "Löschen"));
    li.append(actions);
    return li;
  }

  function ruleEditor(rule) {
    const box = el("div", "editor top");
    const r1 = el("div", "row");
    const name = input("grow", "Was", rule ? rule.name : "", { maxLength: 200 });
    const qty = quantityField(rule ? rule.quantity : "");
    r1.append(el("span", "lbl", rule ? "Regel" : "Neu"), name, qty.num, qty.unit);
    const r2 = el("div", "row");
    const every = input("num", "14", rule ? rule.everyDays : 14, { type: "number", min: 1, max: 365 });
    const next = input("", "", rule ? rule.nextAt : today(), { type: "date" });
    r2.append(el("span", "lbl", "alle"), every, el("span", null, "Tage, nächstes Mal"), next);
    const buttons = el("div", "row");
    buttons.append(
      button("mini", "Speichern", () => {
        const body = { name: name.value, quantity: qty.read(), everyDays: Number(every.value), nextAt: next.value || null };
        editing = null;
        if (rule) run("PUT", "recurring/" + rule.id, body);
        else run("POST", "recurring", body);
      }),
      button("mini quiet", "Abbrechen", () => { editing = null; render(board); }));
    box.append(r1, r2, buttons);
    if (!rule) setTimeout(() => name.focus(), 0);
    return box;
  }

  function renderRules() {
    const list = $("rules");
    list.replaceChildren();
    for (const rule of board.recurring) list.append(ruleRow(rule));
    $("rule-count").textContent = board.recurring.length ? board.recurring.length : "";
    const editor = $("rule-editor");
    editor.replaceChildren();
    if (editing && editing.kind === "rule") {
      const rule = editing.id ? board.recurring.find((r) => r.id === editing.id) : null;
      if (editing.id && !rule) editing = null;
      else editor.append(ruleEditor(rule));
    }
  }

  function render(next) {
    board = next;
    noaccess.hidden = true;
    $("me").textContent = "angemeldet als " + board.me;
    show();
  }

  // MARK: - Feste Knoepfe

  $("item-unit").replaceChildren(...Array.from(unitSelect("Stk").options));
  // Beim Umhaengen der Optionen geht die Vorauswahl verloren - ausdruecklich
  // auf Stueck stellen, sonst steht die letzte Einheit im Feld.
  $("item-unit").value = "Stk";
  $("add-item").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const name = $("item-name").value.trim();
    if (!name) return;
    const typed = { name: $("item-name").value, qty: $("item-qty").value, unit: $("item-unit").value };
    const quantity = composeQuantity(typed.qty, typed.unit);
    $("item-name").value = "";
    $("item-qty").value = "";
    $("item-unit").value = "Stk";
    // Scheitert der Aufruf, steht wieder da, was getippt war - sofern nicht schon
    // der naechste Eintrag im Feld steht.
    if (!(await run("POST", "items", { name, quantity })) && !$("item-name").value) {
      $("item-name").value = typed.name;
      $("item-qty").value = typed.qty;
      $("item-unit").value = typed.unit;
    }
  });
  $("clear").addEventListener("click", () => run("POST", "items/clear-checked"));
  $("new-dish").addEventListener("click", () => { editing = { kind: "dish", id: null }; render(board); });
  $("new-rule").addEventListener("click", () => { editing = { kind: "rule", id: null }; render(board); });
  $("open-chat").addEventListener("click", () => { location.hash = "#chat"; });
  window.addEventListener("hashchange", enterView);

  // Erst das Brett, dann die Sicht aus der Adresse - und einmal nachsehen, ob es
  // den Rezept-Chat fuer diese Person gibt (sonst fehlt der Knopf einfach).
  view = parseHash();
  load().then((ok) => {
    if (!ok) return;
    enterView();
    if (view.name !== "chat") call("GET", "chat").then(setChat).catch(() => {});
  });
  // Zwei Handys, eine Liste: wer die Seite offen hat, sieht die Haken der
  // anderen Person, ohne neu zu laden. Nur auf dem Brett: ein Rezept mit
  // gewaehlten Portionen, der Chat und die Kochansicht bleiben stehen, wie sie sind.
  setInterval(() => { if (!document.hidden && !editing && view.name === "board") load(); }, 30000);
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) return;
    if (view.name === "board") load();
    else if (view.name === "chat") refreshChat();
    else if (view.name === "cook") holdScreen();
  });
})();
