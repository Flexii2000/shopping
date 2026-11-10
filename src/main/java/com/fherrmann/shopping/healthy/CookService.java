package com.fherrmann.shopping.healthy;

import com.fherrmann.shopping.dto.CookResult;
import com.fherrmann.shopping.dto.CookResult.PersonResult;
import com.fherrmann.shopping.model.Dish;
import com.fherrmann.shopping.model.Nutrition;
import com.fherrmann.shopping.security.TokenRegistry;
import com.fherrmann.shopping.service.ShoppingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Kochen": das Rezept wird im Kalorienzaehler zum Gericht - bei allen, die die
 * Einkaufsliste benutzen und Healthy haben (Felix' Wahl, 10.11.: meist essen beide
 * mit). So laesst sich die Portion dort gleich eintragen.
 *
 * <p>Das Gericht heisst dort {@code "Rezept: <Name>"}, wie die Kantine "Kochwerk: ..."
 * heisst: der Kalorienzaehler ordnet ueber den Namen zu, und ein von Hand gepflegtes
 * Gericht gleichen Namens soll nicht ueberschrieben werden.
 *
 * <p>Scheitert es, wird trotzdem gekocht: die Antwort sagt je Person, was war, und
 * kommt immer mit 200.
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

    public CookService(ShoppingService shopping, Healthy healthy, TokenRegistry registry) {
        this.shopping = shopping;
        this.healthy = healthy;
        this.registry = registry;
    }

    public CookResult cook(String me, String dishId) {
        Dish dish = shopping.dish(dishId);
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

        List<Healthy.Outcome> outcomes;
        try {
            outcomes = healthy.writeDish(persons, out);
        } catch (HealthyException e) {
            log.warn("Kochen: „{}“ kam nicht im Kalorienzähler an ({})", dish.name(), e.getMessage());
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
        log.info("Kochen: „{}“ von {} - {}", dish.name(), me,
                results.stream().map(r -> r.person() + " " + r.status()).toList());
        return new CookResult(dish.id(), null, results);
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
