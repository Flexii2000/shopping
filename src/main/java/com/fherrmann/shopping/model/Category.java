package com.fherrmann.shopping.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * Die Kategorien der Liste - in der Reihenfolge eines Supermarkt-Rundgangs,
 * und genau so wird die Liste sortiert: Obst und Gemuese am Eingang, Drogerie
 * und Haushalt am Ende.
 *
 * <p>Der Schluessel ({@link #key}) steht in der Datei und in den Antworten;
 * Beschriftung, Emoji (Web), SF-Symbol (App) und Farbe liefert das Brett mit, damit
 * kein Client die Reihenfolge oder die Namen hart codieren muss.
 */
public enum Category {

    PRODUCE("produce", "Obst & Gemüse", "🥦", "carrot", "#34A853"),
    BAKERY("bakery", "Backwaren", "🍞", "birthday.cake", "#C98B3A"),
    MEAT("meat", "Fleisch & Wurst", "🥩", "fork.knife", "#D9483B"),
    // Eigene Theke, eigenes Bild: Rinderhack mit einem Fisch als Symbol war
    // falsch, und im Laden liegt der Fisch woanders als die Wurst.
    FISH("fish", "Fisch & Meeresfrüchte", "🐟", "fish", "#2E86C1"),
    DAIRY("dairy", "Milchprodukte", "🧀", "mug", "#3B82F6"),
    CANNED("canned", "Konserven", "🥫", "cylinder", "#7F8C9A"),
    STAPLES("staples", "Vorrat & Trockenwaren", "🍝", "cabinet", "#D89A00"),
    SPICES("spices", "Gewürze & Saucen", "🧂", "leaf", "#9B59B6"),
    SNACKS("snacks", "Snacks & Süßes", "🍫", "popcorn", "#E91E63"),
    BEVERAGES("beverages", "Getränke", "🧃", "waterbottle", "#0EA5E9"),
    FROZEN("frozen", "Tiefkühl", "🧊", "snowflake", "#60A5FA"),
    DRUGSTORE("drugstore", "Drogerie", "🧴", "comb", "#8B5CF6"),
    HOUSEHOLD("household", "Haushalt", "🧻", "bubbles.and.sparkles", "#14B8A6"),
    OTHER("other", "Sonstiges", "🛒", "tag", "#9CA3AF");

    private final String key;
    private final String label;
    private final String emoji;
    private final String symbol;
    /** Als {@code #RRGGBB} - dieselbe Farbe im Web und in der App, damit die Kategorien auf einen Blick auseinanderzuhalten sind. */
    private final String color;

    Category(String key, String label, String emoji, String symbol, String color) {
        this.key = key;
        this.label = label;
        this.emoji = emoji;
        this.symbol = symbol;
        this.color = color;
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

    public String color() {
        return color;
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
