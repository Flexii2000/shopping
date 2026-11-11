package com.fherrmann.shopping.service;

/**
 * Ein Rezept wurde angelegt oder geaendert - von Hand, aus dem Chat oder von einer App.
 * Damit kommt es in Healthy an, ohne dass jeder Weg zum Speichern davon wissen muss
 * ({@link com.fherrmann.shopping.healthy.CookService}).
 *
 * @param dishId       das Rezept
 * @param previousName der Name vor dieser Aenderung, wenn sie ihn geaendert hat, sonst
 *                     {@code null}
 */
public record RecipeSaved(String dishId, String previousName) {
}
