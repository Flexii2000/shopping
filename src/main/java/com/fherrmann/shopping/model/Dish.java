package com.fherrmann.shopping.model;

import java.time.Instant;
import java.util.List;

/**
 * Ein Gericht, das man als Ganzes auf die Liste packt: je Zutat ein Eintrag.
 *
 * <p>Bewusst nicht mit dem Kalorienzaehler verknuepft - dort haben Gerichte
 * Naehrwerte je 100 g, hier Zutaten mit Einkaufsmengen. Zwei Fragen, zwei
 * Listen.
 */
public record Dish(String id, String name, List<Ingredient> ingredients, Instant createdAt) {

    public Dish {
        ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
    }
}
