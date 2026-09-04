package com.fherrmann.shopping.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * Die Kategorien der Liste - in der Reihenfolge eines Supermarkt-Rundgangs,
 * und genau so wird die Liste sortiert: Obst und Gemuese am Eingang, Drogerie
 * und Haushalt am Ende.
 *
 * <p>Der Schluessel ({@link #key}) steht in der Datei und in den Antworten;
 * Beschriftung, Emoji (Web) und SF-Symbol (App) liefert das Brett mit, damit
 * kein Client die Reihenfolge oder die Namen hart codieren muss.
 */
public enum Category {

    PRODUCE("produce", "Obst & Gemüse", "🥦", "carrot"),
    BAKERY("bakery", "Backwaren", "🍞", "birthday.cake"),
    MEAT("meat", "Fleisch & Fisch", "🥩", "fish"),
    DAIRY("dairy", "Milchprodukte", "🧀", "mug"),
    CANNED("canned", "Konserven", "🥫", "cylinder"),
    STAPLES("staples", "Vorrat & Trockenwaren", "🍝", "cabinet"),
    SPICES("spices", "Gewürze & Saucen", "🧂", "leaf"),
    SNACKS("snacks", "Snacks & Süßes", "🍫", "popcorn"),
    BEVERAGES("beverages", "Getränke", "🧃", "waterbottle"),
    FROZEN("frozen", "Tiefkühl", "🧊", "snowflake"),
    DRUGSTORE("drugstore", "Drogerie", "🧴", "comb"),
    HOUSEHOLD("household", "Haushalt", "🧻", "bubbles.and.sparkles"),
    OTHER("other", "Sonstiges", "🛒", "tag");

    private final String key;
    private final String label;
    private final String emoji;
    private final String symbol;

    Category(String key, String label, String emoji, String symbol) {
        this.key = key;
        this.label = label;
        this.emoji = emoji;
        this.symbol = symbol;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public String emoji() {
        return emoji;
    }

    public String symbol() {
        return symbol;
    }

    public static Optional<Category> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(c -> c.key.equals(key.trim())).findFirst();
    }

    /** Unbekannt oder fehlend heisst "Sonstiges" - nie {@code null} nach aussen. */
    public static Category orOther(String key) {
        return fromKey(key).orElse(OTHER);
    }
}
