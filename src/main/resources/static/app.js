// Die Einkaufsliste: eine Liste fuer zwei Personen, Gerichte als Ganzes drauf,
// Regeln, die sich selbst drauf setzen. Jede Antwort des Dienstes ist das
// ganze Brett - hier wird nur gezeichnet, nie gerechnet.
(function () {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const banner = $("banner");
  const main = $("main");
  const noaccess = $("noaccess");
  let board = null;
  // Welches Formular gerade offen ist: {kind:'item'|'dish'|'rule', id} - so
  // ueberlebt es das Neuzeichnen nach einer Antwort.
  let editing = null;

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
      noaccess.hidden = false;
      return;
    }
    banner.textContent = err.message;
    banner.hidden = false;
  }

  async function run(method, path, body) {
    try {
      banner.hidden = true;
      render(await call(method, path, body));
    } catch (err) { fail(err); }
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

  function itemRow(item) {
    const li = el("li", "item" + (item.checkedAt ? " checked" : ""));
    const box = el("input");
    box.type = "checkbox";
    box.checked = !!item.checkedAt;
    box.title = item.checkedAt ? "Wieder öffnen" : "Abhaken";
    box.addEventListener("change", () =>
      run(box.checked ? "POST" : "DELETE", "items/" + item.id + "/check"));
    // Das Icon der Kategorie vor dem Namen - die Liste ist danach sortiert,
    // Abschnitte gibt es keine: eine Liste, nur in Rundgang-Reihenfolge.
    const cat = category(item.category);
    const icon = el("span", "icon", cat.emoji);
    icon.title = cat.label;
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
    const qty = input("qty", "Menge", item.quantity, { maxLength: 40 });
    const note = input("grow", "Notiz", item.note, { maxLength: 200 });
    row.append(name, qty, note);
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
        run("PUT", "items/" + item.id, { name: name.value, quantity: qty.value, note: note.value,
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

  // MARK: - Gerichte

  function dishTile(dish) {
    const tile = el("div", "tile");
    tile.append(el("div", "name", dish.name));
    const n = dish.ingredients.length;
    tile.append(el("div", "sub", n === 1 ? "1 Zutat" : n + " Zutaten"));
    const row = el("div", "row");
    row.append(
      button("mini", "Auf die Liste", () => run("POST", "dishes/" + dish.id + "/add"), "Alle Zutaten auf die Liste"),
      button("x", "✎", () => { editing = { kind: "dish", id: dish.id }; render(board); }, "Bearbeiten"),
      button("x del", "×", () => {
        if (confirm("Gericht „" + dish.name + "“ löschen? Einträge auf der Liste bleiben.")) run("DELETE", "dishes/" + dish.id);
      }, "Löschen"));
    tile.append(row);
    return tile;
  }

  // Anlegen und Aendern in einem Formular: Name, dann Zutatenzeilen (Name +
  // Menge), eine leere Zeile immer unten - wer sie befuellt, bekommt die
  // naechste.
  function dishEditor(dish) {
    const box = el("div", "editor top");
    const head = el("div", "row");
    const name = input("grow", "Name des Gerichts", dish ? dish.name : "", { maxLength: 200 });
    head.append(el("span", "lbl", dish ? "Gericht" : "Neu"), name);
    box.append(head);
    const rows = el("div");
    box.append(rows);
    const lines = [];
    function addLine(ing) {
      const row = el("div", "row");
      const n = input("grow", "Zutat", ing ? ing.name : "", { maxLength: 200 });
      const q = input("qty", "Menge", ing ? ing.quantity : "", { maxLength: 40 });
      const rm = button("x del", "×", () => { rows.removeChild(row); lines.splice(lines.indexOf(line), 1); ensureEmpty(); }, "Zutat entfernen");
      const line = { n, q };
      lines.push(line);
      row.append(n, q, rm);
      rows.append(row);
      n.addEventListener("input", ensureEmpty);
    }
    function ensureEmpty() {
      if (!lines.length || lines[lines.length - 1].n.value.trim()) addLine(null);
    }
    for (const ing of (dish ? dish.ingredients : [])) addLine(ing);
    ensureEmpty();
    const buttons = el("div", "row");
    buttons.append(
      button("mini", "Speichern", () => {
        const ingredients = lines
          .filter((l) => l.n.value.trim())
          .map((l) => ({ name: l.n.value, quantity: l.q.value }));
        editing = null;
        if (dish) run("PUT", "dishes/" + dish.id, { name: name.value, ingredients });
        else run("POST", "dishes", { name: name.value, ingredients });
      }),
      button("mini quiet", "Abbrechen", () => { editing = null; render(board); }));
    box.append(buttons);
    if (!dish) setTimeout(() => name.focus(), 0);
    return box;
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
      else editor.append(dishEditor(dish));
    }
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
    const qty = input("qty", "Menge", rule ? rule.quantity : "", { maxLength: 40 });
    r1.append(el("span", "lbl", rule ? "Regel" : "Neu"), name, qty);
    const r2 = el("div", "row");
    const every = input("num", "14", rule ? rule.everyDays : 14, { type: "number", min: 1, max: 365 });
    const next = input("", "", rule ? rule.nextAt : today(), { type: "date" });
    r2.append(el("span", "lbl", "alle"), every, el("span", null, "Tage, nächstes Mal"), next);
    const buttons = el("div", "row");
    buttons.append(
      button("mini", "Speichern", () => {
        const body = { name: name.value, quantity: qty.value, everyDays: Number(every.value), nextAt: next.value || null };
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
    main.hidden = false;
    $("me").textContent = "angemeldet als " + board.me;
    renderItems();
    renderDishes();
    renderRules();
  }

  // MARK: - Feste Knoepfe

  $("add-item").addEventListener("submit", (ev) => {
    ev.preventDefault();
    const name = $("item-name").value.trim();
    if (!name) return;
    const quantity = $("item-qty").value.trim();
    $("item-name").value = "";
    $("item-qty").value = "";
    run("POST", "items", { name, quantity: quantity || null });
  });
  $("clear").addEventListener("click", () => run("POST", "items/clear-checked"));
  $("new-dish").addEventListener("click", () => { editing = { kind: "dish", id: null }; render(board); });
  $("new-rule").addEventListener("click", () => { editing = { kind: "rule", id: null }; render(board); });

  load();
  // Zwei Handys, eine Liste: wer die Seite offen hat, sieht die Haken der
  // anderen Person, ohne neu zu laden.
  setInterval(() => { if (!document.hidden && !editing) load(); }, 30000);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) load(); });
})();
