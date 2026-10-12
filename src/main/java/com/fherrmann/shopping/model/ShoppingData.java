package com.fherrmann.shopping.model;

import java.util.List;
import java.util.Map;

/**
 * Alles, was in {@code shopping.json} steht.
 *
 * @param learned   von Hand gewaehlte Kategorien, je normalisiertem Namen -
 *                  was einmal "Kokosmilch = Vorrat" war, bleibt es, auch ueber
 *                  Gerichte und Regeln
 * @param clientIds Kennungen, die eine App fuer neue Eintraege vergeben hat, und
 *                  der Eintrag, der daraus wurde (seit 12.10.) - in aelteren
 *                  Dateien fehlt das Feld
 */
public record ShoppingData(List<Item> items, List<Dish> dishes, List<RecurringRule> recurring,
                           Map<String, String> learned, Map<String, ClientRef> clientIds) {

    public ShoppingData {
        items = items == null ? List.of() : List.copyOf(items);
        dishes = dishes == null ? List.of() : List.copyOf(dishes);
        recurring = recurring == null ? List.of() : List.copyOf(recurring);
        learned = learned == null ? Map.of() : Map.copyOf(learned);
        clientIds = clientIds == null ? Map.of() : Map.copyOf(clientIds);
    }

    public ShoppingData(List<Item> items, List<Dish> dishes, List<RecurringRule> recurring,
                        Map<String, String> learned) {
        this(items, dishes, recurring, learned, Map.of());
    }

    public static ShoppingData empty() {
        return new ShoppingData(List.of(), List.of(), List.of(), Map.of(), Map.of());
    }

    public ShoppingData withItems(List<Item> items) {
        return new ShoppingData(items, dishes, recurring, learned, clientIds);
    }

    public ShoppingData withDishes(List<Dish> dishes) {
        return new ShoppingData(items, dishes, recurring, learned, clientIds);
    }

    public ShoppingData withRecurring(List<RecurringRule> recurring) {
        return new ShoppingData(items, dishes, recurring, learned, clientIds);
    }

    public ShoppingData withLearned(Map<String, String> learned) {
        return new ShoppingData(items, dishes, recurring, learned, clientIds);
    }

    public ShoppingData withClientIds(Map<String, ClientRef> clientIds) {
        return new ShoppingData(items, dishes, recurring, learned, clientIds);
    }
}
