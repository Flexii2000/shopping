package com.fherrmann.shopping.recipe;

/**
 * Was der Rezept-Agent auf eine Nachricht antwortet: ein paar Saetze und, wenn es
 * einen gibt, der Rezeptvorschlag.
 */
public record RecipeAnswer(String reply, RecipeDraft draft) {
}
