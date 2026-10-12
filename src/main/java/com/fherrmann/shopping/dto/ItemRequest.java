package com.fherrmann.shopping.dto;

/**
 * Anlegen und Aendern eines Eintrags. Fehlt {@code quantity} beim Aendern,
 * gibt es keine mehr. {@code category} ist die von Hand gewaehlte Kategorie -
 * ohne sie raet der Dienst, mit ihr merkt er sich die Wahl fuer diesen Namen.
 *
 * <p>{@code id} nur beim Anlegen, wenn die App die Kennung selbst vergibt (seit
 * 12.10.): ohne Netz steht der Eintrag sofort auf der Liste und laesst sich abhaken,
 * bevor der Dienst ihn kennt. Dieselbe Kennung zweimal legt nichts an.
 */
public record ItemRequest(String name, String quantity, String note, String category, String id) {

    public ItemRequest(String name, String quantity, String note, String category) {
        this(name, quantity, note, category, null);
    }
}
