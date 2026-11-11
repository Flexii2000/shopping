package com.fherrmann.shopping.service;

import com.fherrmann.shopping.dto.Board;
import com.fherrmann.shopping.dto.ClearCheckedRequest;
import com.fherrmann.shopping.dto.DishAddRequest;
import com.fherrmann.shopping.dto.DishRequest;
import com.fherrmann.shopping.dto.ItemRequest;
import com.fherrmann.shopping.dto.RecurringRequest;
import com.fherrmann.shopping.dto.ScaledDish;
import com.fherrmann.shopping.model.Category;
import com.fherrmann.shopping.model.ClientRef;
import com.fherrmann.shopping.model.Dish;
import com.fherrmann.shopping.model.Ingredient;
import com.fherrmann.shopping.model.Item;
import com.fherrmann.shopping.model.Nutrition;
import com.fherrmann.shopping.model.RecurringRule;
import com.fherrmann.shopping.model.ShoppingData;
import com.fherrmann.shopping.repository.ShoppingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Die Regeln: eine Liste fuer zwei Personen, Rezepte als Ganzes drauf,
 * und was sich von selbst drauf setzt.
 *
 * <p>Die Regeln, die nicht offensichtlich sind:
 * <ul>
 * <li><b>Abgehaktes bleibt bis Mitternacht sichtbar</b> (durchgestrichen,
 *     Berliner Zeit) und faellt dann aus dem Brett - lange genug, um ein
 *     Versehen zu bemerken, kurz genug, dass die Liste am naechsten Einkauf
 *     leer anfaengt. In der Datei bleibt es noch {@code shopping.history-days}.</li>
 * <li><b>Abhaken ist idempotent</b>: der erste Haken zaehlt, ein zweiter
 *     aendert nichts - zwei Handys im selben Supermarkt kommen sich so nicht
 *     in die Quere.</li>
 * <li><b>Eine Regel setzt nur einen offenen Eintrag</b> auf einmal, und ihr
 *     Rhythmus zaehlt ab dem Kauf: das Abhaken schiebt {@code nextAt} weiter,
 *     nicht der Kalender.</li>
 * <li><b>Die Liste ist nach Kategorien sortiert</b> - in der Reihenfolge eines
 *     Supermarkt-Rundgangs ({@link Category}). Die Kategorie raet der
 *     {@link Categorizer}; wer sie von Hand setzt, bringt dem Dienst den
 *     Namen bei, und der gilt dann auch fuer Rezepte und Regeln.</li>
 * <li><b>Die App darf die Kennung eines neuen Eintrags vergeben</b> (seit 12.10.):
 *     ohne Netz steht er sofort auf der Liste und laesst sich abhaken, bevor der
 *     Dienst ihn kennt. Geht er in einem offenen gleichen Namens auf, merkt sich
 *     {@code clientIds}, wohin - Haken und Loeschen mit der Kennung der App treffen
 *     dann diesen. Dieselbe Kennung zweimal legt nichts an: nachgesendet wuerden aus
 *     2 Litern Milch sonst 4.</li>
 * </ul>
 *
 * <p>Jedes Aendern laedt, aendert und speichert in einem Zug ({@code synchronized}) -
 * auch der Stundenlauf der Regeln. Nachgesendetes aus zwei Postausgaengen kommt in
 * Schueben, und zwei Schreiber, die beide den alten Stand gelesen haben, verloeren
 * sonst den Eintrag des anderen.
 */
@Service
public class ShoppingService {

    public static final int MAX_NAME = 200;
    /** Grenzen eines Rezepts - auch der Rezept-Agent kuerzt seine Entwuerfe darauf. */
    public static final int MAX_PORTIONS = 99;
    public static final int MAX_MINUTES = 1440;
    public static final int MAX_GRAMS_PER_PORTION = 5000;
    public static final int MAX_STEPS = 40;
    public static final int MAX_STEP = 500;
    public static final int MAX_INGREDIENTS = 60;
    /** Was der Kalorienzaehler je 100 g hoechstens annimmt - mehr gibt es in keinem Essen. */
    public static final double MAX_KCAL_PER_100G = 1000;
    public static final double MAX_MACRO_PER_100G = 100;
    /** Wer einen Eintrag ueber eine Regel bekommt, sieht das als Urheber. */
    static final String BY_RULE = "regel";
    /** Kennungen, die eine App vergibt: kleingeschriebene UUIDs, wie der Dienst sie auch erzeugt. */
    private static final Pattern CLIENT_ID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final ShoppingRepository repository;
    private final Categorizer categorizer;
    private final Clock clock;
    private final Duration history;
    private final ApplicationEventPublisher events;

    @Autowired
    public ShoppingService(ShoppingRepository repository, Categorizer categorizer, Clock clock,
                           @Value("${shopping.history-days:90}") int historyDays,
                           ApplicationEventPublisher events) {
        this.repository = repository;
        this.categorizer = categorizer;
        this.clock = clock;
        this.history = Duration.ofDays(historyDays);
        this.events = events;
    }

    /** Fuer Tests, die nicht mitlesen, was ein gespeichertes Rezept ausloest. */
    public ShoppingService(ShoppingRepository repository, Categorizer categorizer, Clock clock, int historyDays) {
        this(repository, categorizer, clock, historyDays, event -> { });
    }

    // MARK: - Das Brett

    public Board board(String me) {
        return board(repository.load(), me);
    }

    Board board(ShoppingData data, String me) {
        LocalDate today = today();
        List<Item> items = new ArrayList<>();
        for (Item item : data.items()) {
            if (isVisible(item, today)) {
                items.add(item);
            }
        }
        items.sort((a, b) -> {
            boolean ac = isChecked(a), bc = isChecked(b);
            if (ac != bc) {
                return ac ? 1 : -1;
            }
            if (ac) {
                return b.checkedAt().compareTo(a.checkedAt());
            }
            int byCategory = Integer.compare(Category.orOther(a.category()).ordinal(),
                    Category.orOther(b.category()).ordinal());
            return byCategory != 0 ? byCategory : a.addedAt().compareTo(b.addedAt());
        });
        List<Dish> dishes = new ArrayList<>(data.dishes());
        dishes.sort(Comparator.comparing(Dish::name, String.CASE_INSENSITIVE_ORDER));
        List<RecurringRule> rules = new ArrayList<>(data.recurring());
        rules.sort(Comparator.comparing(RecurringRule::nextAt)
                .thenComparing(RecurringRule::name, String.CASE_INSENSITIVE_ORDER));
        return new Board(me, items, dishes, rules);
    }

    static boolean isChecked(Item item) {
        return item.checkedAt() != null;
    }

    /** Offen, oder heute abgehakt. */
    boolean isVisible(Item item, LocalDate today) {
        return !isChecked(item) || LocalDate.ofInstant(item.checkedAt(), clock.getZone()).equals(today);
    }

    // MARK: - Eintraege

    public synchronized Board createItem(String me, ItemRequest request) {
        // "gemischtes Hack 200g" in einem Feld: die Menge steckt im Namen und
        // wird herausgeloest - erst danach wird der Name zugeordnet.
        QuantityParser.Split split = QuantityParser.resolve(
                cleanName(request == null ? null : request.name(), "Ein Eintrag braucht einen Namen."),
                clean(request.quantity()));
        String clientId = cleanClientId(request.id());
        String name = split.name();
        ShoppingData loaded = repository.load();
        if (clientId != null && knows(loaded, clientId)) {
            // Derselbe Eintrag noch einmal - aus dem Postausgang nachgesendet, weil die
            // erste Antwort verloren ging. Nicht noch einmal zusammenfassen.
            return board(loaded, me);
        }
        ShoppingData data = learn(loaded, name, request.category());
        List<Item> items = new ArrayList<>(data.items());
        Item same = openItemNamed(items, name);
        if (same != null) {
            // Derselbe Name noch einmal - egal wie geschrieben - wird EIN
            // Eintrag: die Mengen zusammen, nicht zwei Zeilen Rinderhack.
            // Eine von Hand gewaehlte Kategorie gilt auch fuer den Eintrag.
            String category = isBlank(request.category()) ? same.category() : categorize(data, name, split.quantity());
            Item merged = same.merged(QuantityParser.merge(same.quantity(), split.quantity()),
                    mergeNotes(same.note(), clean(request.note())), null, null);
            items.set(items.indexOf(same), merged.withText(merged.name(), merged.quantity(), merged.note(), category));
            return save(remember(data.withItems(items), clientId, same.id()), me);
        }
        String id = clientId != null ? clientId : ShoppingRepository.newId();
        items.add(new Item(id, name, split.quantity(), clean(request.note()),
                categorize(data, name, split.quantity()), Instant.now(clock), me, null, null, null, null));
        return save(remember(data.withItems(items), clientId, id), me);
    }

    /** Ob der Dienst diese Kennung einer App schon kennt - als Eintrag oder als aufgegangene. */
    private static boolean knows(ShoppingData data, String clientId) {
        return data.clientIds().containsKey(clientId) || data.items().stream().anyMatch(i -> i.id().equals(clientId));
    }

    /**
     * Merkt sich, welcher Eintrag aus welcher Kennung einer App wurde. Auch wenn es
     * derselbe ist: wird er geloescht und kommt das Anlegen spaeter noch einmal an,
     * holte es ihn sonst zurueck.
     */
    private ShoppingData remember(ShoppingData data, String clientId, String itemId) {
        return clientId == null ? data : remember(data, Map.of(clientId, itemId));
    }

    private ShoppingData remember(ShoppingData data, Map<String, String> clientToItem) {
        if (clientToItem.isEmpty()) {
            return data;
        }
        Map<String, ClientRef> refs = new HashMap<>(data.clientIds());
        Instant now = Instant.now(clock);
        clientToItem.forEach((clientId, itemId) -> refs.put(clientId, new ClientRef(itemId, now)));
        return data.withClientIds(refs);
    }

    /**
     * Die Kennung eines Eintrags auf der Liste - auch fuer eine, die eine App vergeben
     * hat und die in einem offenen gleichen Namens aufging. Unbekannt bleibt sie, wie
     * sie ist, und fuehrt zu "nicht gefunden".
     */
    private static String resolve(ShoppingData data, String id) {
        if (data.items().stream().anyMatch(i -> i.id().equals(id))) {
            return id;
        }
        ClientRef ref = data.clientIds().get(id);
        return ref == null ? id : ref.itemId();
    }

    /**
     * Die Kennung einer App: leer heisst keine. Sonst nur eine kleingeschriebene UUID -
     * die App fragt spaeter mit genau dieser Zeichenkette, ein still umgeschriebener
     * Wert fuehrte dann ins Leere.
     */
    private static String cleanClientId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String id = raw.trim();
        if (!CLIENT_ID.matcher(id).matches()) {
            throw badRequest("Die Kennung ist keine UUID.");
        }
        return id;
    }

    /** Der offene Eintrag mit diesem Namen - Gross-/Kleinschreibung und Leerraum zaehlen nicht. */
    private static Item openItemNamed(List<Item> items, String name) {
        String key = nameKey(name);
        return items.stream().filter(i -> !isChecked(i) && nameKey(i.name()).equals(key)).findFirst().orElse(null);
    }

    static String nameKey(String name) {
        return name.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String mergeNotes(String a, String b) {
        if (isBlank(a)) {
            return isBlank(b) ? null : b;
        }
        if (isBlank(b) || a.equalsIgnoreCase(b)) {
            return a;
        }
        return a + ", " + b;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /** Ohne {@code category} im Rumpf wird neu geraten - auch wenn nur die Menge geaendert wurde. */
    public synchronized Board updateItem(String me, String id, ItemRequest request) {
        QuantityParser.Split split = QuantityParser.resolve(
                cleanName(request == null ? null : request.name(), "Ein Eintrag braucht einen Namen."),
                clean(request.quantity()));
        String name = split.name();
        ShoppingData data = learn(repository.load(), name, request.category());
        String target = resolve(data, id);
        findItem(data, target);
        String category = categorize(data, name, split.quantity());
        return save(data.withItems(data.items().stream()
                .map(i -> i.id().equals(target) ? i.withText(name, split.quantity(), clean(request.note()), category) : i)
                .toList()), me);
    }

    /**
     * Eine von Hand gewaehlte Kategorie merken - je normalisiertem Namen.
     * Danach bekommt derselbe Name diese Kategorie ueberall, auch als Zutat
     * eines Rezepts oder aus einer Regel.
     */
    private static ShoppingData learn(ShoppingData data, String name, String categoryKey) {
        if (categoryKey == null) {
            return data;
        }
        Category category = Category.fromKey(categoryKey)
                .orElseThrow(() -> badRequest("Unbekannte Kategorie: " + categoryKey));
        Map<String, String> learned = new HashMap<>(data.learned());
        learned.put(Categorizer.normalize(name), category.key());
        return data.withLearned(learned);
    }

    /**
     * Gelerntes zuerst, dann die Verpackung, dann das Woerterbuch: was in
     * Dosen oder Glaesern zaehlt, steht bei den Konserven - "Tomaten 3 Dosen"
     * liegen nicht beim Gemuese.
     */
    private String categorize(ShoppingData data, String name, String quantity) {
        String learned = data.learned().get(Categorizer.normalize(name));
        if (learned != null) {
            return learned;
        }
        if (QuantityParser.isCanOrJar(quantity)) {
            return Category.CANNED.key();
        }
        return categorizer.classify(name, data.learned()).key();
    }

    public synchronized Board deleteItem(String me, String id) {
        ShoppingData data = repository.load();
        String target = resolve(data, id);
        findItem(data, target);
        return save(data.withItems(data.items().stream().filter(i -> !i.id().equals(target)).toList()), me);
    }

    /**
     * Abhaken. Ein zweiter Haken aendert nichts - Zeitpunkt und Name des
     * ersten bleiben. Haengt eine Regel am Eintrag, zaehlt ihr Rhythmus ab
     * jetzt.
     */
    public synchronized Board check(String me, String id) {
        ShoppingData data = repository.load();
        Item item = findItem(data, resolve(data, id));
        if (isChecked(item)) {
            return board(data, me);
        }
        Instant now = Instant.now(clock);
        ShoppingData updated = data.withItems(data.items().stream()
                .map(i -> i.id().equals(item.id()) ? i.withChecked(now, me) : i).toList());
        if (item.ruleId() != null) {
            LocalDate today = today();
            updated = updated.withRecurring(updated.recurring().stream()
                    .map(r -> r.id().equals(item.ruleId()) ? r.withNextAt(today.plusDays(r.everyDays())) : r)
                    .toList());
        }
        return save(updated, me);
    }

    /** Haken zurueck. Bei einem offenen Eintrag passiert nichts. */
    public synchronized Board uncheck(String me, String id) {
        ShoppingData data = repository.load();
        Item item = findItem(data, resolve(data, id));
        if (!isChecked(item)) {
            return board(data, me);
        }
        return save(data.withItems(data.items().stream()
                .map(i -> i.id().equals(item.id()) ? i.withChecked(null, null) : i).toList()), me);
    }

    /**
     * Alle abgehakten Eintraege loeschen - wirklich, nicht nur ausblenden.
     * Wer das drueckt, ist mit dem Einkauf durch; ein Verlauf abgehakter
     * Milchtueten ist niemandem etwas wert.
     */
    public Board clearChecked(String me) {
        return clearChecked(me, null);
    }

    /**
     * Mit Liste nur die genannten, sofern noch abgehakt: die App schickt, was sie
     * abgehakt sah. Kommt das offline Gedrueckte spaeter an, bleibt ein Eintrag, den
     * die andere Person inzwischen abgehakt hat - sie will ihn vielleicht noch sehen.
     */
    public synchronized Board clearChecked(String me, ClearCheckedRequest request) {
        ShoppingData data = repository.load();
        if (request == null || request.ids() == null) {
            return save(data.withItems(data.items().stream().filter(i -> !isChecked(i)).toList()), me);
        }
        Set<String> ids = new HashSet<>();
        for (String id : request.ids()) {
            if (id != null) {
                ids.add(resolve(data, id));
            }
        }
        return save(data.withItems(data.items().stream()
                .filter(i -> !(isChecked(i) && ids.contains(i.id()))).toList()), me);
    }

    // MARK: - Rezepte (im Brett und in der Datei weiter "dishes")

    public synchronized Board createDish(String me, DishRequest request) {
        createRecipe(me, request, null);
        return board(me);
    }

    /**
     * Legt ein Rezept an und gibt es zurueck - der Rezept-Chat braucht die Kennung, um
     * am Entwurf zu vermerken, was aus ihm wurde.
     *
     * @param source {@code "chat"} fuer einen gespeicherten Entwurf, sonst {@code null}
     */
    public synchronized Dish createRecipe(String me, DishRequest request, String source) {
        String name = cleanDishName(request);
        ShoppingData data = repository.load();
        requireUniqueName(data, name, null);
        Integer portions = bounded(request.portions(), MAX_PORTIONS, "Portionen");
        Integer minutes = bounded(request.minutes(), MAX_MINUTES, "Minuten");
        Double grams = cleanGrams(request.gramsPerPortion());
        Nutrition nutrition = cleanNutrition(request.nutrition());
        requirePlausible(nutrition, grams);
        Dish dish = new Dish(ShoppingRepository.newId(), name, ingredients(request, null), Instant.now(clock),
                portions, minutes, cleanSteps(request.steps()), nutrition, grams, source, me);
        List<Dish> dishes = new ArrayList<>(data.dishes());
        dishes.add(dish);
        repository.save(data.withDishes(dishes));
        events.publishEvent(new RecipeSaved(dish.id(), null));
        return dish;
    }

    /**
     * Aendert ein Rezept. <b>Fehlt ein Feld, bleibt es</b> - aeltere Apps schicken nur
     * Name und Zutaten, und ihr "Speichern" darf Schritte und Naehrwerte nicht loeschen.
     * Der leere Wert loescht ({@code steps: []}, {@code portions: 0}, {@code minutes: 0},
     * {@code gramsPerPortion: 0}, {@code nutrition: {}}). Nur die Zutaten ersetzen die
     * alten ganz, wie immer.
     */
    public synchronized Board updateDish(String me, String id, DishRequest request) {
        String name = cleanDishName(request);
        ShoppingData data = repository.load();
        Dish existing = findDish(data, id);
        // Nur beim Umbenennen pruefen: zwei gleichnamige von frueher muessen sich
        // weiter bearbeiten lassen.
        if (!nameKey(name).equals(nameKey(existing.name()))) {
            requireUniqueName(data, name, id);
        }
        Integer portions = request.portions() == null ? existing.portions()
                : bounded(request.portions(), MAX_PORTIONS, "Portionen");
        Integer minutes = request.minutes() == null ? existing.minutes()
                : bounded(request.minutes(), MAX_MINUTES, "Minuten");
        Double grams = request.gramsPerPortion() == null ? existing.gramsPerPortion()
                : cleanGrams(request.gramsPerPortion());
        Nutrition nutrition = request.nutrition() == null ? existing.nutrition()
                : cleanNutrition(request.nutrition());
        List<String> steps = request.steps() == null ? existing.steps() : cleanSteps(request.steps());
        requirePlausible(nutrition, grams);
        Dish updated = existing.withContent(name, ingredients(request, existing), portions, minutes, steps,
                nutrition, grams);
        Board board = save(data.withDishes(data.dishes().stream().map(d -> d.id().equals(id) ? updated : d).toList()), me);
        events.publishEvent(new RecipeSaved(id, existing.name().equals(name) ? null : existing.name()));
        return board;
    }

    /** Loescht das Rezept. Eintraege, die daraus schon auf der Liste stehen, bleiben. */
    public synchronized Board deleteDish(String me, String id) {
        ShoppingData data = repository.load();
        findDish(data, id);
        return save(data.withDishes(data.dishes().stream().filter(d -> !d.id().equals(id)).toList()), me);
    }

    /** Ein Rezept, wie es gespeichert ist - fuer "Kochen". */
    public Dish dish(String id) {
        return findDish(repository.load(), id);
    }

    /** Alle Rezepte, wie sie gespeichert sind - fuer den Abgleich mit Healthy beim Start. */
    public List<Dish> dishes() {
        return repository.load().dishes();
    }

    /**
     * Die Zutaten eines Rezepts fuer eine andere Portionenzahl. Gerechnet wird hier und
     * nicht in der Weboberflaeche - die zeichnet nur, was der Dienst liefert.
     */
    public ScaledDish scaled(String id, Integer portions) {
        Dish dish = findDish(repository.load(), id);
        int base = basePortions(dish);
        int wanted = portions == null ? base : validPortions(portions);
        List<ScaledDish.ScaledIngredient> ingredients = new ArrayList<>();
        for (Ingredient ingredient : dish.ingredients()) {
            QuantityParser.Scaled scaled = QuantityParser.scale(ingredient.quantity(), wanted, base);
            ingredients.add(new ScaledDish.ScaledIngredient(ingredient.name(), scaled.quantity(),
                    ingredient.pantry(), scaled.scaled()));
        }
        return new ScaledDish(dish.id(), wanted, base, ingredients);
    }

    /**
     * Das Rezept als Ganzes auf die Liste: je Zutat ein Eintrag, mit dem Namen des
     * Rezepts als Notiz. Steht eine Zutat schon offen auf der Liste, geht sie in
     * diesem Eintrag auf - die Mengen kommen zusammen.
     */
    public Board addDish(String me, String id) {
        return addDish(me, id, null);
    }

    /**
     * Mit den Kennungen, die die App je Zutat vergeben hat (Zuordnung ueber den Namen).
     * Kennt der Dienst eine davon schon, kam dasselbe Rezept schon an - nachgesendet,
     * weil die Antwort verloren ging -, und es aendert sich nichts.
     *
     * <p>Mit {@code portions} werden die Mengen von den Portionen des Rezepts
     * umgerechnet, bevor sie auf die Liste gehen; {@code skip} nennt Zutaten, die nicht
     * mit sollen. Ohne beides alles wie vor den Rezepten.
     */
    public synchronized Board addDish(String me, String id, DishAddRequest request) {
        ShoppingData data = repository.load();
        Dish dish = findDish(data, id);
        if (dish.ingredients().isEmpty()) {
            throw badRequest("„" + dish.name() + "“ hat keine Zutaten.");
        }
        Integer portions = request == null ? null : request.portions();
        int base = portions == null ? 1 : basePortions(dish);
        int wanted = portions == null ? 1 : validPortions(portions);
        Set<String> skipped = new HashSet<>();
        if (request != null && request.skip() != null) {
            for (String name : request.skip()) {
                if (name != null) {
                    skipped.add(nameKey(name));
                }
            }
        }
        Map<String, String> clientIdByName = new HashMap<>();
        if (request != null && request.items() != null) {
            for (DishAddRequest.Entry entry : request.items()) {
                String clientId = entry == null || entry.name() == null ? null : cleanClientId(entry.id());
                if (clientId != null) {
                    if (knows(data, clientId)) {
                        return board(data, me);
                    }
                    clientIdByName.putIfAbsent(nameKey(entry.name()), clientId);
                }
            }
        }
        Instant now = Instant.now(clock);
        List<Item> items = new ArrayList<>(data.items());
        Map<String, String> clientToItem = new HashMap<>();
        for (Ingredient ingredient : dish.ingredients()) {
            if (skipped.contains(nameKey(ingredient.name()))) {
                continue;
            }
            // Ohne portions sind base und wanted gleich: die Menge bleibt, wie sie ist.
            String quantity = portions == null ? ingredient.quantity()
                    : QuantityParser.scale(ingredient.quantity(), wanted, base).quantity();
            String clientId = clientIdByName.remove(nameKey(ingredient.name()));
            Item same = openItemNamed(items, ingredient.name());
            if (same != null) {
                items.set(items.indexOf(same), same.merged(QuantityParser.merge(same.quantity(), quantity),
                        mergeNotes(same.note(), dish.name()), dish.id(), null));
                if (clientId != null) {
                    clientToItem.put(clientId, same.id());
                }
                continue;
            }
            String itemId = clientId != null ? clientId : ShoppingRepository.newId();
            items.add(new Item(itemId, ingredient.name(), quantity, dish.name(),
                    categorize(data, ingredient.name(), quantity), now, me, null, null, dish.id(), null));
            if (clientId != null) {
                clientToItem.put(clientId, itemId);
            }
            // Gleicher Zeitstempel, aber die Reihenfolge der Zutaten soll
            // erhalten bleiben - die Sortierung ist stabil, das reicht.
        }
        return save(remember(data.withItems(items), clientToItem), me);
    }

    private static int basePortions(Dish dish) {
        if (dish.portions() == null) {
            throw badRequest("„" + dish.name() + "“ hat keine Portionsangabe.");
        }
        return dish.portions();
    }

    private static int validPortions(int portions) {
        if (portions < 1 || portions > MAX_PORTIONS) {
            throw badRequest("Portionen: 1 bis " + MAX_PORTIONS + ".");
        }
        return portions;
    }

    // MARK: - Regeln

    public synchronized Board createRule(String me, RecurringRequest request) {
        QuantityParser.Split split = QuantityParser.resolve(cleanRuleName(request), clean(request.quantity()));
        RecurringRule rule = new RecurringRule(ShoppingRepository.newId(), split.name(),
                split.quantity(), cleanEveryDays(request),
                request.nextAt() == null ? today() : request.nextAt(), Instant.now(clock));
        ShoppingData data = repository.load();
        List<RecurringRule> rules = new ArrayList<>(data.recurring());
        rules.add(rule);
        // Sofort nachsehen: eine Regel "ab heute" soll nicht bis zum
        // naechsten Stundenlauf warten.
        return save(applyRules(data.withRecurring(rules)), me);
    }

    public synchronized Board updateRule(String me, String id, RecurringRequest request) {
        QuantityParser.Split split = QuantityParser.resolve(cleanRuleName(request), clean(request.quantity()));
        String name = split.name();
        int everyDays = cleanEveryDays(request);
        ShoppingData data = repository.load();
        RecurringRule existing = findRule(data, id);
        LocalDate nextAt = request.nextAt() == null ? existing.nextAt() : request.nextAt();
        return save(applyRules(data.withRecurring(data.recurring().stream()
                .map(r -> r.id().equals(id) ? r.withText(name, split.quantity(), everyDays, nextAt) : r)
                .toList())), me);
    }

    /** Loescht die Regel. Ein offener Eintrag von ihr bleibt auf der Liste - man will ihn ja noch kaufen. */
    public synchronized Board deleteRule(String me, String id) {
        ShoppingData data = repository.load();
        findRule(data, id);
        return save(data.withRecurring(data.recurring().stream().filter(r -> !r.id().equals(id)).toList()), me);
    }

    /** Der Stundenlauf: faellige Regeln setzen ihren Eintrag. Speichert nur, wenn sich etwas getan hat. */
    public synchronized void runRecurring() {
        ShoppingData data = repository.load();
        ShoppingData updated = applyRules(data);
        if (!updated.equals(data)) {
            repository.save(updated);
        }
    }

    /**
     * Je Regel mit {@code nextAt <= heute} ein Eintrag - ausser es steht schon
     * ein offener von ihr auf der Liste. Dann bleibt auch {@code nextAt}, wo
     * es ist: abhaken schiebt es weiter, und wer den Eintrag loescht statt
     * ihn zu kaufen, bekommt ihn beim naechsten Lauf wieder - die Regel gilt.
     */
    ShoppingData applyRules(ShoppingData data) {
        LocalDate today = today();
        Instant now = Instant.now(clock);
        List<Item> items = new ArrayList<>(data.items());
        List<RecurringRule> rules = new ArrayList<>();
        for (RecurringRule rule : data.recurring()) {
            boolean due = !rule.nextAt().isAfter(today);
            boolean open = items.stream().anyMatch(i -> rule.id().equals(i.ruleId()) && !isChecked(i));
            if (due && !open) {
                Item same = openItemNamed(items, rule.name());
                if (same != null) {
                    // Steht schon jemand von Hand auf der Liste, haengt sich die
                    // Regel daran: Abhaken schiebt sie dann weiter. Die Menge
                    // bleibt, wie sie eingetragen wurde - nur wo keine steht,
                    // kommt die der Regel.
                    items.set(items.indexOf(same), same.merged(
                            isBlank(same.quantity()) ? rule.quantity() : same.quantity(), same.note(), null, rule.id()));
                } else {
                    items.add(new Item(ShoppingRepository.newId(), rule.name(), rule.quantity(), null,
                            categorize(data, rule.name(), rule.quantity()), now, BY_RULE, null, null, null, rule.id()));
                }
                rules.add(rule.withNextAt(today.plusDays(rule.everyDays())));
            } else {
                rules.add(rule);
            }
        }
        return new ShoppingData(items, data.dishes(), rules, data.learned(), data.clientIds());
    }

    /** Der Aufraeumer: abgehakte Eintraege, die aelter sind als die Frist, fliegen aus der Datei. */
    public synchronized void cleanup() {
        ShoppingData data = repository.load();
        Instant limit = Instant.now(clock).minus(history);
        List<Item> kept = data.items().stream()
                .filter(i -> !isChecked(i) || i.checkedAt().isAfter(limit)).toList();
        // Kennungen der Apps nach derselben Frist: was so lange im Postausgang lag,
        // kommt nicht mehr.
        Map<String, ClientRef> refs = new HashMap<>();
        data.clientIds().forEach((clientId, ref) -> {
            if (ref.at() == null || ref.at().isAfter(limit)) {
                refs.put(clientId, ref);
            }
        });
        if (kept.size() != data.items().size() || refs.size() != data.clientIds().size()) {
            repository.save(data.withItems(kept).withClientIds(refs));
        }
    }

    // MARK: - Kleinkram

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private Board save(ShoppingData data, String me) {
        repository.save(data);
        return board(data, me);
    }

    private static Item findItem(ShoppingData data, String id) {
        return data.items().stream().filter(i -> i.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Eintrag nicht gefunden."));
    }

    private static Dish findDish(ShoppingData data, String id) {
        return data.dishes().stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rezept nicht gefunden."));
    }

    private static RecurringRule findRule(ShoppingData data, String id) {
        return data.recurring().stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Regel nicht gefunden."));
    }

    private static String cleanDishName(DishRequest request) {
        return cleanName(request == null ? null : request.name(), "Ein Rezept braucht einen Namen.");
    }

    /**
     * Ein Name, ein Rezept - Gross-/Kleinschreibung und Leerraum zaehlen nicht. Der
     * Kalorienzaehler ordnet das gekochte Rezept ueber den Namen zu; zwei gleichnamige
     * ueberschrieben sich dort gegenseitig.
     *
     * @param exceptId das Rezept, das gerade umbenannt wird, oder {@code null}
     */
    private static void requireUniqueName(ShoppingData data, String name, String exceptId) {
        String key = nameKey(name);
        boolean taken = data.dishes().stream()
                .anyMatch(d -> !d.id().equals(exceptId) && nameKey(d.name()).equals(key));
        if (taken) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ein Rezept mit diesem Namen gibt es schon.");
        }
    }

    /**
     * Die Zutaten aus dem Rumpf. Fehlt bei einer {@code pantry}, behaelt sie das
     * Kennzeichen der gleichnamigen Zutat von vorher - eine aeltere App, die es nicht
     * kennt, soll es beim Speichern nicht loeschen.
     */
    private static List<Ingredient> ingredients(DishRequest request, Dish existing) {
        List<Ingredient> result = new ArrayList<>();
        if (request.ingredients() == null) {
            return result;
        }
        for (DishRequest.IngredientRequest ingredient : request.ingredients()) {
            String name = ingredient == null ? null : clean(ingredient.name());
            // Leere Zeilen im Formular sind keine Zutaten.
            if (name == null) {
                continue;
            }
            if (name.length() > MAX_NAME) {
                throw badRequest("Eine Zutat darf höchstens " + MAX_NAME + " Zeichen haben.");
            }
            QuantityParser.Split split = QuantityParser.resolve(name, clean(ingredient.quantity()));
            Boolean pantry = ingredient.pantry();
            if (pantry == null && existing != null) {
                String key = nameKey(split.name());
                pantry = existing.ingredients().stream()
                        .filter(i -> nameKey(i.name()).equals(key)).findFirst()
                        .map(Ingredient::pantry).orElse(false);
            }
            result.add(new Ingredient(split.name(), split.quantity(), pantry));
        }
        if (result.size() > MAX_INGREDIENTS) {
            throw badRequest("Ein Rezept darf höchstens " + MAX_INGREDIENTS + " Zutaten haben.");
        }
        return result;
    }

    /** Leer und 0 heissen: keine Angabe. */
    private static Integer bounded(Integer value, int max, String what) {
        if (value == null || value == 0) {
            return null;
        }
        if (value < 1 || value > max) {
            throw badRequest(what + ": 1 bis " + max + ".");
        }
        return value;
    }

    private static Double cleanGrams(Double value) {
        if (value == null || value == 0) {
            return null;
        }
        if (value.isNaN() || value < 1 || value > MAX_GRAMS_PER_PORTION) {
            throw badRequest("Das Gewicht einer Portion: 1 bis " + MAX_GRAMS_PER_PORTION + " g.");
        }
        return value;
    }

    private static List<String> cleanSteps(List<String> raw) {
        List<String> steps = new ArrayList<>();
        if (raw == null) {
            return steps;
        }
        for (String line : raw) {
            String step = clean(line);
            if (step == null) {
                continue;
            }
            if (step.length() > MAX_STEP) {
                throw badRequest("Ein Schritt darf höchstens " + MAX_STEP + " Zeichen haben.");
            }
            steps.add(step);
        }
        if (steps.size() > MAX_STEPS) {
            throw badRequest("Ein Rezept darf höchstens " + MAX_STEPS + " Schritte haben.");
        }
        return steps;
    }

    /** Alle vier oder keiner; ganz ohne Werte heisst: keine Naehrwerte. */
    private static Nutrition cleanNutrition(DishRequest.NutritionRequest raw) {
        if (raw == null || (raw.kcal() == null && raw.proteinG() == null && raw.carbsG() == null && raw.fatG() == null)) {
            return null;
        }
        Double[] values = {raw.kcal(), raw.proteinG(), raw.carbsG(), raw.fatG()};
        for (Double value : values) {
            if (value == null) {
                throw badRequest("Nährwerte brauchen kcal, Eiweiß, Kohlenhydrate und Fett.");
            }
            if (value.isNaN() || value.isInfinite() || value < 0) {
                throw badRequest("Nährwerte können nicht negativ sein.");
            }
        }
        return new Nutrition(raw.kcal(), raw.proteinG(), raw.carbsG(), raw.fatG());
    }

    /**
     * Naehrwerte gelten je Portion und brauchen ihr Gewicht - der Kalorienzaehler
     * rechnet je 100 g. Was dort nicht hineinpasst, wird schon hier abgelehnt, nicht
     * erst beim Kochen.
     */
    private static void requirePlausible(Nutrition nutrition, Double gramsPerPortion) {
        if (nutrition == null) {
            return;
        }
        if (gramsPerPortion == null) {
            throw badRequest("Nährwerte brauchen das Gewicht einer Portion.");
        }
        if (!fitsFood(nutrition, gramsPerPortion)) {
            throw badRequest("Die Nährwerte passen nicht zum Gewicht der Portion.");
        }
    }

    /** Ob die Werte je 100 g in die Grenzen des Kalorienzaehlers passen. */
    public static boolean fitsFood(Nutrition nutrition, double gramsPerPortion) {
        double factor = 100 / gramsPerPortion;
        return nutrition.kcal() * factor <= MAX_KCAL_PER_100G
                && nutrition.proteinG() * factor <= MAX_MACRO_PER_100G
                && nutrition.carbsG() * factor <= MAX_MACRO_PER_100G
                && nutrition.fatG() * factor <= MAX_MACRO_PER_100G;
    }

    private static String cleanRuleName(RecurringRequest request) {
        return cleanName(request == null ? null : request.name(), "Eine Regel braucht einen Namen.");
    }

    private static int cleanEveryDays(RecurringRequest request) {
        if (request.everyDays() == null || request.everyDays() < 1) {
            throw badRequest("Alle wie viele Tage? Mindestens 1.");
        }
        return request.everyDays();
    }

    private static String cleanName(String raw, String missing) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw badRequest(missing);
        }
        if (name.length() > MAX_NAME) {
            throw badRequest("Der Name darf höchstens " + MAX_NAME + " Zeichen haben.");
        }
        return name;
    }

    /** Getrimmt, und leer heisst: nicht angegeben. */
    static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return value.isEmpty() ? null : value;
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
