package com.fherrmann.shopping.dto;

import java.util.List;

/**
 * Ein Gericht auf die Liste, mit den Kennungen, die die App je Zutat vergeben hat - sie
 * zeigt die Eintraege ohne Netz sofort und braucht fuer Haken und Aendern schon ein Ziel.
 * Ohne Rumpf vergibt der Dienst die Kennungen, wie bisher.
 */
public record DishAddRequest(List<Entry> items) {

    /** Eine Zutat, ueber ihren Namen zugeordnet. */
    public record Entry(String name, String id) {
    }
}
