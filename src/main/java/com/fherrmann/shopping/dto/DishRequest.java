package com.fherrmann.shopping.dto;

import java.util.List;

/**
 * Anlegen und Aendern eines Rezepts.
 *
 * <p>Beim Aendern gilt: <b>fehlt ein Feld, bleibt es, wie es ist.</b> Aeltere Apps
 * schicken nur {@code name} und {@code ingredients} - ein Tipp auf "Speichern" dort
 * darf Schritte und Naehrwerte nicht loeschen. Wer etwas loeschen will, schickt den
 * leeren Wert: {@code steps: []}, {@code portions: 0}, {@code minutes: 0},
 * {@code gramsPerPortion: 0}, {@code nutrition: {}}. Nur die Zutatenliste ersetzt
 * die alte wie bisher ganz; fehlt bei einer Zutat {@code pantry}, behaelt eine
 * gleichnamige ihr Kennzeichen.
 *
 * <p>Beim Anlegen heisst "fehlt" und "leer" dasselbe: nicht angegeben.
 */
public record DishRequest(String name, List<IngredientRequest> ingredients, Integer portions, Integer minutes,
                          List<String> steps, NutritionRequest nutrition, Double gramsPerPortion) {

    /** Der Rumpf aelterer Apps und der einfachste Fall: Name und Zutaten. */
    public DishRequest(String name, List<IngredientRequest> ingredients) {
        this(name, ingredients, null, null, null, null, null);
    }

    public record IngredientRequest(String name, String quantity, Boolean pantry) {

        public IngredientRequest(String name, String quantity) {
            this(name, quantity, null);
        }
    }

    /** Je Portion. Alle vier oder keiner; ganz ohne Werte ({@code {}}) heisst: keine Naehrwerte mehr. */
    public record NutritionRequest(Double kcal, Double proteinG, Double carbsG, Double fatG) {
    }
}
