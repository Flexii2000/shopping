package com.fherrmann.shopping.healthy;

import com.fherrmann.shopping.dto.CookResult;
import com.fherrmann.shopping.dto.CookResult.PersonResult;
import com.fherrmann.shopping.model.Dish;
import com.fherrmann.shopping.model.Nutrition;
import com.fherrmann.shopping.security.TokenRegistry;
import com.fherrmann.shopping.service.RecipeSaved;
import com.fherrmann.shopping.service.ShoppingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Rezepte im Kalorienzaehler: jedes Rezept mit Naehrwerten ist dort ein Gericht - bei
 * allen, die die Einkaufsliste benutzen und Healthy haben (Felix' Wahl, 10.11.: meist
 * essen beide mit).
 *
 * <p><b>Schon beim Speichern</b> (Felix, 11.11.): wer ein Rezept anlegt oder aendert,
 * kann es gleich danach in Healthy eintragen - nicht erst, nachdem "Kochen" einmal
 * offen war. Das laeuft im Hintergrund und haelt das Speichern nie auf; scheitert es,
 * holen "Kochen" (mit Antwort je Person) oder der Abgleich beim naechsten Start es nach.
 * Ein umbenanntes Rezept benennt das Gericht dort um. Geloeschte Rezepte bleiben dort
 * stehen - die Person hat das Gericht vielleicht schon eingetragen und gepflegt.
 *
 * <p>Das Gericht heisst dort {@code "Rezept: <Name>"}, wie die Kantine "Kochwerk: ..."
 * heisst: der Kalorienzaehler ordnet ueber den Namen zu, und ein von Hand gepflegtes
 * Gericht gleichen Namens soll nicht ueberschrieben werden.
 *
 * <p>"Kochen" fragt noch einmal und zeigt das Ergebnis; scheitert es, wird trotzdem
 * gekocht: die Antwort sagt je Person, was war, und kommt immer mit 200.
 */
@Service
public class CookService {

    private static final Logger log = LoggerFactory.getLogger(CookService.class);

    static final String PREFIX = "Rezept: ";
    /** So lang darf ein Gericht im Kalorienzaehler heissen. */
    static final int MAX_FOOD_NAME = 120;

    private final ShoppingService shopping;
    private final Healthy healthy;
    private final TokenRegistry registry;
    /** Ein Faden: zwei schnelle Aenderungen kommen in ihrer Reihenfolge an. */
    private final Executor background;

    @Autowired
    public CookService(ShoppingService shopping, Healthy healthy, TokenRegistry registry) {
        this(shopping, healthy, registry, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "healthy-dishes");
            thread.setDaemon(true);
            return thread;
        }));
    }

    CookService(ShoppingService shopping, Healthy healthy, TokenRegistry registry, Executor background) {
        this.shopping = shopping;
        this.healthy = healthy;
        this.registry = registry;
        this.background = background;
    }

    public CookResult cook(String me, String dishId) {
        Dish dish = shopping.dish(dishId);
        CookResult result = write(dish, null);
        if (result.reason() == null) {
            log.info("Kochen: „{}“ von {} - {}", dish.name(), me, summary(result));
        }
        return result;
    }

    /** Angelegt oder geaendert: im Hintergrund nach Healthy. */
    @EventListener
    public void onRecipeSaved(RecipeSaved saved) {
        background.execute(() -> safely(() -> publish(saved.dishId(), saved.previousName())));
    }

    /** Einmal nach dem Start alle - was beim Speichern nicht ankam, kommt so nach. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStarted() {
        background.execute(() -> safely(this::publishAll));
    }

    /** Ein Fehler hier darf nie bis zum Speichern durchschlagen - und nicht still verschwinden. */
    private static void safely(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.warn("Rezept nach Healthy gescheitert", e);
        }
    }

    void publish(String dishId, String previousName) {
        Dish dish;
        try {
            dish = shopping.dish(dishId);
        } catch (ResponseStatusException e) {
            return; // inzwischen geloescht
        }
        CookResult result = write(dish, previousName);
        if (result.reason() == null) {
            if (previousName == null) {
                log.info("Rezept in Healthy: „{}“ - {}", dish.name(), summary(result));
            } else {
                log.info("Rezept in Healthy: „{}“ (vorher „{}“) - {}", dish.name(), previousName, summary(result));
            }
        }
    }

    void publishAll() {
        if (!healthy.isConfigured()) {
            return;
        }
        Map<String, Integer> counts = new TreeMap<>();
        int recipes = 0;
        for (Dish dish : shopping.dishes()) {
            CookResult result = write(dish, null);
            if (result.reason() != null) {
                continue;
            }
            recipes++;
            result.healthy().forEach(r -> counts.merge(r.status(), 1, Integer::sum));
            if (!result.healthy().isEmpty()
                    && result.healthy().stream().allMatch(r -> PersonResult.FAILED.equals(r.status()))) {
                // Kalorienzaehler nicht da (die Warnung steht schon im Log) - der Rest
                // scheiterte genauso.
                break;
            }
        }
        if (recipes > 0) {
            log.info("Rezepte mit Healthy abgeglichen: {} Rezepte - {}", recipes, counts);
        }
    }

    /**
     * Schreibt das Rezept in den Kalorienzaehler. Wirft nie: ohne Naehrwerte oder ohne
     * Token sagt {@code reason} warum nicht, ein Ausfall steht je Person im Ergebnis.
     */
    CookResult write(Dish dish, String previousName) {
        Nutrition nutrition = dish.nutrition();
        Double grams = dish.gramsPerPortion();
        if (nutrition == null || grams == null || grams <= 0) {
            return new CookResult(dish.id(), CookResult.NO_NUTRITION, List.of());
        }
        if (!healthy.isConfigured()) {
            return new CookResult(dish.id(), CookResult.NOT_CONFIGURED, List.of());
        }
        List<String> persons = registry.names();
        HealthyDish out = new HealthyDish(foodName(dish.name()),
                per100(nutrition.kcal(), grams), per100(nutrition.proteinG(), grams),
                per100(nutrition.carbsG(), grams), per100(nutrition.fatG(), grams), grams);
        String before = previousName == null ? null : foodName(previousName);

        List<Healthy.Outcome> outcomes;
        try {
            outcomes = healthy.writeDish(persons, out, before == null || before.equals(out.name()) ? null : before);
        } catch (HealthyException e) {
            log.warn("„{}“ kam nicht im Kalorienzähler an ({})", dish.name(), e.getMessage());
            return new CookResult(dish.id(), null, persons.stream()
                    .map(person -> new PersonResult(person, PersonResult.FAILED, e.getMessage())).toList());
        }

        List<PersonResult> results = new ArrayList<>();
        Set<String> answered = new HashSet<>();
        for (Healthy.Outcome outcome : outcomes) {
            answered.add(outcome.person());
            // Wer kein Healthy hat, steht nicht in der Liste - das ist kein Fehler.
            if (!Healthy.Outcome.NOT_LINKED.equals(outcome.status()) && persons.contains(outcome.person())) {
                results.add(new PersonResult(outcome.person(), outcome.status(), outcome.message()));
            }
        }
        for (String person : persons) {
            if (!answered.contains(person)) {
                results.add(new PersonResult(person, PersonResult.FAILED, "Keine Antwort vom Kalorienzähler."));
            }
        }
        return new CookResult(dish.id(), null, results);
    }

    private static List<String> summary(CookResult result) {
        return result.healthy().stream().map(r -> r.person() + " " + r.status()).toList();
    }

    /** {@code "Rezept: "} plus der Name, gekuerzt auf das, was der Kalorienzaehler annimmt. */
    static String foodName(String name) {
        String full = PREFIX + name.strip().replaceAll("\\s+", " ");
        return full.length() <= MAX_FOOD_NAME ? full : full.substring(0, MAX_FOOD_NAME - 1).stripTrailing() + "…";
    }

    /** Je Portion zu je 100 g, auf zwei Nachkommastellen. */
    static double per100(double perPortion, double gramsPerPortion) {
        return BigDecimal.valueOf(perPortion * 100 / gramsPerPortion).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
