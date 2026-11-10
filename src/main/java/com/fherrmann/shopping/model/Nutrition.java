package com.fherrmann.shopping.model;

/**
 * Naehrwerte eines Rezepts <b>je Portion</b>. Alle vier oder keiner - der
 * Kalorienzaehler nimmt ein Gericht nur ganz.
 *
 * <p>Der Kalorienzaehler rechnet je 100 g; umgerechnet wird erst beim Kochen, mit
 * {@code gramsPerPortion} des Rezepts.
 */
public record Nutrition(Double kcal, Double proteinG, Double carbsG, Double fatG) {
}
