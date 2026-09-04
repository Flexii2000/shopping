package com.fherrmann.shopping.dto;

import com.fherrmann.shopping.model.Category;
import com.fherrmann.shopping.model.Dish;
import com.fherrmann.shopping.model.Item;
import com.fherrmann.shopping.model.RecurringRule;

import java.util.Arrays;
import java.util.List;

/**
 * Alles, was ein Client braucht, in einer Antwort - jede Antwort des
 * Dienstes ist eins.
 *
 * <p>Eine Form fuer alles, statt je Aenderung ein anderes Stueck: die
 * Weboberflaeche zeichnet daraus neu, die App ersetzt ihren Stand (und legt
 * genau diese Antwort in ihren Offline-Cache).
 *
 * @param me         der Name hinter dem Token, mit dem gefragt wurde
 * @param items      offene zuerst - nach Kategorie in Rundgang-Reihenfolge,
 *                   darin aelteste oben -, dann die heute abgehakten (zuletzt
 *                   abgehakte oben). Aeltere abgehakte kommen nicht mit.
 * @param categories alle Kategorien in Sortierreihenfolge, mit Beschriftung,
 *                   Emoji und SF-Symbol - damit kein Client sie hart codiert
 */
public record Board(String me, List<Item> items, List<Dish> dishes, List<RecurringRule> recurring,
                    List<CategoryView> categories) {

    public Board(String me, List<Item> items, List<Dish> dishes, List<RecurringRule> recurring) {
        this(me, items, dishes, recurring, CATEGORIES);
    }

    public record CategoryView(String key, String label, String emoji, String symbol) {
    }

    public static final List<CategoryView> CATEGORIES = Arrays.stream(Category.values())
            .map(c -> new CategoryView(c.key(), c.label(), c.emoji(), c.symbol()))
            .toList();
}
