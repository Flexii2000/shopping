package com.fherrmann.shopping.dto;

/**
 * Anlegen und Aendern eines Eintrags. Fehlt {@code quantity} beim Aendern,
 * gibt es keine mehr. {@code category} ist die von Hand gewaehlte Kategorie -
 * ohne sie raet der Dienst, mit ihr merkt er sich die Wahl fuer diesen Namen.
 */
public record ItemRequest(String name, String quantity, String note, String category) {
}
