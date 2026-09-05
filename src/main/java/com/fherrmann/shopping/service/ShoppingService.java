package com.fherrmann.shopping.service;

import com.fherrmann.shopping.dto.Board;
import com.fherrmann.shopping.dto.DishRequest;
import com.fherrmann.shopping.dto.ItemRequest;
import com.fherrmann.shopping.dto.RecurringRequest;
import com.fherrmann.shopping.model.Category;
import com.fherrmann.shopping.model.Dish;
import com.fherrmann.shopping.model.Ingredient;
import com.fherrmann.shopping.model.Item;
import com.fherrmann.shopping.model.RecurringRule;
import com.fherrmann.shopping.model.ShoppingData;
import com.fherrmann.shopping.repository.ShoppingRepository;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.List;
import java.util.Map;

/**
 * Die Regeln: eine Liste fuer zwei Personen, Gerichte als Ganzes drauf,
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
 *     Namen bei, und der gilt dann auch fuer Gerichte und Regeln.</li>
 * </ul>
 */
@Service
public class ShoppingService {

    static final int MAX_NAME = 200;
    /** Wer einen Eintrag ueber eine Regel bekommt, sieht das als Urheber. */
    static final String BY_RULE = "regel";

    private final ShoppingRepository repository;
    private final Categorizer categorizer;
    private final Clock clock;
    private final Duration history;

    public ShoppingService(ShoppingRepository repository, Categorizer categorizer, Clock clock,
                           @Value("${shopping.history-days:90}") int historyDays) {
        this.repository = repository;
        this.categorizer = categorizer;
        this.clock = clock;
        this.history = Duration.ofDays(historyDays);
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

    public Board createItem(String me, ItemRequest request) {
        // "gemischtes Hack 200g" in einem Feld: die Menge steckt im Namen und
        // wird herausgeloest - erst danach wird der Name zugeordnet.
        QuantityParser.Split split = QuantityParser.resolve(
                cleanName(request == null ? null : request.name(), "Ein Eintrag braucht einen Namen."),
                clean(request.quantity()));
        String name = split.name();
        ShoppingData data = learn(repository.load(), name, request.category());
        List<Item> items = new ArrayList<>(data.items());
        items.add(new Item(ShoppingRepository.newId(), name, split.quantity(), clean(request.note()),
                categorize(data, name), Instant.now(clock), me, null, null, null, null));
        return save(data.withItems(items), me);
    }

    /** Ohne {@code category} im Rumpf wird neu geraten - auch wenn nur die Menge geaendert wurde. */
    public Board updateItem(String me, String id, ItemRequest request) {
        QuantityParser.Split split = QuantityParser.resolve(
                cleanName(request == null ? null : request.name(), "Ein Eintrag braucht einen Namen."),
                clean(request.quantity()));
        String name = split.name();
        ShoppingData data = learn(repository.load(), name, request.category());
        findItem(data, id);
        String category = categorize(data, name);
        return save(data.withItems(data.items().stream()
                .map(i -> i.id().equals(id) ? i.withText(name, split.quantity(), clean(request.note()), category) : i)
                .toList()), me);
    }

    /**
     * Eine von Hand gewaehlte Kategorie merken - je normalisiertem Namen.
     * Danach bekommt derselbe Name diese Kategorie ueberall, auch als Zutat
     * eines Gerichts oder aus einer Regel.
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

    private String categorize(ShoppingData data, String name) {
        return categorizer.classify(name, data.learned()).key();
    }

    public Board deleteItem(String me, String id) {
        ShoppingData data = repository.load();
        findItem(data, id);
        return save(data.withItems(data.items().stream().filter(i -> !i.id().equals(id)).toList()), me);
    }

    /**
     * Abhaken. Ein zweiter Haken aendert nichts - Zeitpunkt und Name des
     * ersten bleiben. Haengt eine Regel am Eintrag, zaehlt ihr Rhythmus ab
     * jetzt.
     */
    public Board check(String me, String id) {
        ShoppingData data = repository.load();
        Item item = findItem(data, id);
        if (isChecked(item)) {
            return board(data, me);
        }
        Instant now = Instant.now(clock);
        ShoppingData updated = data.withItems(data.items().stream()
                .map(i -> i.id().equals(id) ? i.withChecked(now, me) : i).toList());
        if (item.ruleId() != null) {
            LocalDate today = today();
            updated = updated.withRecurring(updated.recurring().stream()
                    .map(r -> r.id().equals(item.ruleId()) ? r.withNextAt(today.plusDays(r.everyDays())) : r)
                    .toList());
        }
        return save(updated, me);
    }

    /** Haken zurueck. Bei einem offenen Eintrag passiert nichts. */
    public Board uncheck(String me, String id) {
        ShoppingData data = repository.load();
        Item item = findItem(data, id);
        if (!isChecked(item)) {
            return board(data, me);
        }
        return save(data.withItems(data.items().stream()
                .map(i -> i.id().equals(id) ? i.withChecked(null, null) : i).toList()), me);
    }

    /**
     * Alle abgehakten Eintraege loeschen - wirklich, nicht nur ausblenden.
     * Wer das drueckt, ist mit dem Einkauf durch; ein Verlauf abgehakter
     * Milchtueten ist niemandem etwas wert.
     */
    public Board clearChecked(String me) {
        ShoppingData data = repository.load();
        return save(data.withItems(data.items().stream().filter(i -> !isChecked(i)).toList()), me);
    }

    // MARK: - Gerichte

    public Board createDish(String me, DishRequest request) {
        Dish dish = new Dish(ShoppingRepository.newId(), cleanDishName(request), ingredients(request), Instant.now(clock));
        ShoppingData data = repository.load();
        List<Dish> dishes = new ArrayList<>(data.dishes());
        dishes.add(dish);
        return save(data.withDishes(dishes), me);
    }

    public Board updateDish(String me, String id, DishRequest request) {
        String name = cleanDishName(request);
        List<Ingredient> ingredients = ingredients(request);
        ShoppingData data = repository.load();
        Dish existing = findDish(data, id);
        return save(data.withDishes(data.dishes().stream()
                .map(d -> d.id().equals(id) ? new Dish(existing.id(), name, ingredients, existing.createdAt()) : d)
                .toList()), me);
    }

    /** Loescht das Gericht. Eintraege, die daraus schon auf der Liste stehen, bleiben. */
    public Board deleteDish(String me, String id) {
        ShoppingData data = repository.load();
        findDish(data, id);
        return save(data.withDishes(data.dishes().stream().filter(d -> !d.id().equals(id)).toList()), me);
    }

    /**
     * Das Gericht als Ganzes auf die Liste: je Zutat ein Eintrag, mit dem
     * Gerichtnamen als Notiz. Doppelte sind erlaubt - zwei Gerichte mit
     * Zwiebeln sind zwei Eintraege, die Mengen unterscheiden sich ja.
     */
    public Board addDish(String me, String id) {
        ShoppingData data = repository.load();
        Dish dish = findDish(data, id);
        if (dish.ingredients().isEmpty()) {
            throw badRequest("„" + dish.name() + "“ hat keine Zutaten.");
        }
        Instant now = Instant.now(clock);
        List<Item> items = new ArrayList<>(data.items());
        for (Ingredient ingredient : dish.ingredients()) {
            items.add(new Item(ShoppingRepository.newId(), ingredient.name(), ingredient.quantity(), dish.name(),
                    categorize(data, ingredient.name()), now, me, null, null, dish.id(), null));
            // Gleicher Zeitstempel, aber die Reihenfolge der Zutaten soll
            // erhalten bleiben - die Sortierung ist stabil, das reicht.
        }
        return save(data.withItems(items), me);
    }

    // MARK: - Regeln

    public Board createRule(String me, RecurringRequest request) {
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

    public Board updateRule(String me, String id, RecurringRequest request) {
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
    public Board deleteRule(String me, String id) {
        ShoppingData data = repository.load();
        findRule(data, id);
        return save(data.withRecurring(data.recurring().stream().filter(r -> !r.id().equals(id)).toList()), me);
    }

    /** Der Stundenlauf: faellige Regeln setzen ihren Eintrag. Speichert nur, wenn sich etwas getan hat. */
    public void runRecurring() {
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
                items.add(new Item(ShoppingRepository.newId(), rule.name(), rule.quantity(), null,
                        categorize(data, rule.name()), now, BY_RULE, null, null, null, rule.id()));
                rules.add(rule.withNextAt(today.plusDays(rule.everyDays())));
            } else {
                rules.add(rule);
            }
        }
        return new ShoppingData(items, data.dishes(), rules, data.learned());
    }

    /** Der Aufraeumer: abgehakte Eintraege, die aelter sind als die Frist, fliegen aus der Datei. */
    public void cleanup() {
        ShoppingData data = repository.load();
        Instant limit = Instant.now(clock).minus(history);
        List<Item> kept = data.items().stream()
                .filter(i -> !isChecked(i) || i.checkedAt().isAfter(limit)).toList();
        if (kept.size() != data.items().size()) {
            repository.save(data.withItems(kept));
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Gericht nicht gefunden."));
    }

    private static RecurringRule findRule(ShoppingData data, String id) {
        return data.recurring().stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Regel nicht gefunden."));
    }

    private static String cleanDishName(DishRequest request) {
        return cleanName(request == null ? null : request.name(), "Ein Gericht braucht einen Namen.");
    }

    private static List<Ingredient> ingredients(DishRequest request) {
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
            result.add(new Ingredient(split.name(), split.quantity()));
        }
        return result;
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
