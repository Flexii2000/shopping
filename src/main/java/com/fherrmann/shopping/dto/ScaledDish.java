package com.fherrmann.shopping.dto;

import java.util.List;

/**
 * Die Zutaten eines Rezepts, umgerechnet auf eine Portionenzahl. Eine der wenigen
 * Antworten, die nicht das ganze Brett sind: sie aendert nichts, sie rechnet nur.
 *
 * @param portions     die gewuenschte Portionenzahl
 * @param basePortions fuer so viele gelten die gespeicherten Mengen
 */
public record ScaledDish(String dishId, int portions, int basePortions, List<ScaledIngredient> ingredients) {

    /**
     * @param quantity die umgerechnete Menge, oder {@code null} ohne Menge
     * @param scaled   falsch, wenn die Menge sich nicht lesen liess und deshalb so
     *                 dasteht, wie sie gespeichert ist - die Oberflaechen zeigen sie blasser
     */
    public record ScaledIngredient(String name, String quantity, boolean pantry, boolean scaled) {
    }
}
