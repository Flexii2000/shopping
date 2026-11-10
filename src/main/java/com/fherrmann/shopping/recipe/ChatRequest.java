package com.fherrmann.shopping.recipe;

/**
 * Eine neue Nachricht.
 *
 * @param id die Kennung, die die App vergeben hat (kleingeschriebene UUID). Dieselbe
 *           noch einmal haengt nichts an: steht die Nachricht noch ohne Antwort da,
 *           wird nur die Antwort neu angefordert.
 */
public record ChatRequest(String text, String id) {
}
