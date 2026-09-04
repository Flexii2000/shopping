package com.fherrmann.shopping.model;

import java.util.List;
import java.util.Map;

/**
 * Alles, was in {@code shopping.json} steht.
 *
 * @param learned von Hand gewaehlte Kategorien, je normalisiertem Namen -
 *                was einmal "Kokosmilch = Vorrat" war, bleibt es, auch ueber
 *                Gerichte und Regeln
 */
public record ShoppingData(List<Item> items, List<Dish> dishes, List<RecurringRule> recurring,
                           Map<String, String> learned) {

    public ShoppingData {
        items = items == null ? List.of() : List.copyOf(items);
        dishes = dishes == null ? List.of() : List.copyOf(dishes);
        recurring = recurring == null ? List.of() : List.copyOf(recurring);
        learned = learned == null ? Map.of() : Map.copyOf(learned);
    }

    public static ShoppingData empty() {
        return new ShoppingData(List.of(), List.of(), List.of(), Map.of());
    }

    public ShoppingData withItems(List<Item> items) {
        return new ShoppingData(items, dishes, recurring, learned);
    }

    public ShoppingData withDishes(List<Dish> dishes) {
        return new ShoppingData(items, dishes, recurring, learned);
    }

    public ShoppingData withRecurring(List<RecurringRule> recurring) {
        return new ShoppingData(items, dishes, recurring, learned);
    }

    public ShoppingData withLearned(Map<String, String> learned) {
        return new ShoppingData(items, dishes, recurring, learned);
    }
}
