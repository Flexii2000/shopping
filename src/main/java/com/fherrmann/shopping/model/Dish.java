package com.fherrmann.shopping.model;

import java.time.Instant;
import java.util.List;

/**
 * Ein Rezept: Zutaten, die man als Ganzes auf die Liste packt - je Zutat ein
 * Eintrag -, und seit November 2026 auf Wunsch Portionen, Schritte und
 * Naehrwerte. Die Klasse und der Schluessel {@code dishes} im Brett heissen
 * weiter "Dish": aeltere Apps lesen genau das, und die neuen Felder
 * ueberlesen sie.
 *
 * <p>Ein Gericht von frueher ist ein Rezept ohne die neuen Felder. Alle sind
 * deshalb Wrapper-Typen und duerfen fehlen - mit einem primitiven Feld liesse
 * sich die Datei von gestern nicht mehr lesen (Jackson 3).
 *
 * <p>Verknuepft mit dem Kalorienzaehler ist ein Rezept nur ueber "Kochen":
 * dann legt der Dienst es dort als Gericht an (je 100 g, gerechnet aus
 * {@code nutrition} und {@code gramsPerPortion}).
 *
 * <p>Keine Hilfsmethoden der Form {@code isX()} oder {@code getX()} an diesem
 * Record - Jackson schriebe sie als Feld in die Datei und ins Brett.
 *
 * @param portions        fuer wie viele Portionen die Mengen gelten; ohne laesst
 *                        sich nicht umrechnen
 * @param minutes         Zubereitungszeit
 * @param steps           die Schritte in Reihenfolge, nie {@code null}
 * @param nutrition       je Portion, braucht {@code gramsPerPortion}
 * @param gramsPerPortion Gewicht einer fertigen Portion
 * @param source          {@code "chat"}, wenn es aus einem Entwurf des Rezept-Chats
 *                        gespeichert wurde, sonst {@code null}
 * @param createdBy       der Name hinter dem Token, das es angelegt hat; bei
 *                        aelteren {@code null}
 */
public record Dish(String id, String name, List<Ingredient> ingredients, Instant createdAt,
                   Integer portions, Integer minutes, List<String> steps, Nutrition nutrition,
                   Double gramsPerPortion, String source, String createdBy) {

    public Dish {
        ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /** Ein Gericht wie vor den Rezepten: Name und Zutaten. */
    public Dish(String id, String name, List<Ingredient> ingredients, Instant createdAt) {
        this(id, name, ingredients, createdAt, null, null, null, null, null, null, null);
    }

    /** Derselbe Eintrag mit neuem Inhalt - Kennung, Zeitpunkt, Herkunft und Urheber bleiben. */
    public Dish withContent(String name, List<Ingredient> ingredients, Integer portions, Integer minutes,
                            List<String> steps, Nutrition nutrition, Double gramsPerPortion) {
        return new Dish(id, name, ingredients, createdAt, portions, minutes, steps, nutrition,
                gramsPerPortion, source, createdBy);
    }
}
