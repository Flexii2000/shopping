package com.fherrmann.shopping.healthy;

/**
 * Ein Gericht in der Form des Kalorienzaehlers: Naehrwerte <b>je 100 g</b> und das
 * Gewicht einer Portion.
 */
public record HealthyDish(String name, double kcal, double proteinG, double carbsG, double fatG, double portionG) {
}
