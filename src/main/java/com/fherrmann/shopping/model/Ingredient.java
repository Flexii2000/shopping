package com.fherrmann.shopping.model;

/**
 * Eine Zutat eines Rezepts - wird beim "Auf die Liste" zu einem {@link Item}.
 *
 * @param pantry hat man meist da (Salz, Oel): beim "Auf die Liste" ist sie nicht
 *               vorgewaehlt. In aelteren Dateien fehlt das Feld und heisst nein.
 *               Ein {@code Boolean}, kein {@code boolean}: Jackson 3 scheitert an
 *               einem fehlenden primitiven Feld, und die Datei von gestern waere
 *               dann nicht mehr lesbar.
 */
public record Ingredient(String name, String quantity, Boolean pantry) {

    public Ingredient {
        pantry = Boolean.TRUE.equals(pantry);
    }

    public Ingredient(String name, String quantity) {
        this(name, quantity, false);
    }
}
