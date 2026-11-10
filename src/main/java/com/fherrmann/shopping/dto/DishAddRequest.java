package com.fherrmann.shopping.dto;

import java.util.List;

/**
 * Ein Rezept auf die Liste. Ohne Rumpf alles wie immer: jede Zutat, die Mengen wie
 * gespeichert, die Kennungen vergibt der Dienst.
 *
 * @param items    die Kennungen, die die App je Zutat vergeben hat - sie zeigt die
 *                 Eintraege ohne Netz sofort und braucht fuer Haken und Aendern schon
 *                 ein Ziel
 * @param portions fuer so viele Portionen: die Mengen werden von den Portionen des
 *                 Rezepts umgerechnet. Ohne bleiben sie, wie sie gespeichert sind.
 * @param skip     Namen der Zutaten, die nicht mit sollen (Vorrat, schon da)
 */
public record DishAddRequest(List<Entry> items, Integer portions, List<String> skip) {

    public DishAddRequest(List<Entry> items) {
        this(items, null, null);
    }

    /** Eine Zutat, ueber ihren Namen zugeordnet. */
    public record Entry(String name, String id) {
    }
}
