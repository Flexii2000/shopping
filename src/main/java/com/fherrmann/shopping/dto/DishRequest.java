package com.fherrmann.shopping.dto;

import java.util.List;

/** Anlegen und Aendern eines Gerichts - die Zutatenliste ersetzt die alte ganz. */
public record DishRequest(String name, List<IngredientRequest> ingredients) {

    public record IngredientRequest(String name, String quantity) {
    }
}
