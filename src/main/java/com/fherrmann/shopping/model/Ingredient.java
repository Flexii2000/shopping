package com.fherrmann.shopping.model;

/** Eine Zutat eines Gerichts - wird beim "Auf die Liste" zu einem {@link Item}. */
public record Ingredient(String name, String quantity) {
}
