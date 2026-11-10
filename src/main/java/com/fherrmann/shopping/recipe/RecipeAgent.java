package com.fherrmann.shopping.recipe;

import java.util.List;

/**
 * Beantwortet eine Nachricht im Rezept-Chat.
 *
 * <p>Eigene Schnittstelle, damit die Auftragsverwaltung nichts von der Claude-Session
 * dahinter weiss und Tests eine feste Antwort einsetzen koennen.
 */
public interface RecipeAgent {

    /** Ob der Rezept-Chat auf diesem Server eingerichtet ist. */
    boolean isAvailable();

    /**
     * @param recipeNames die Namen der gespeicherten Rezepte - damit kein Vorschlag so
     *                    heisst wie eines, das es schon gibt
     * @param history     das Gespraech bis hierher, aelteste zuerst, ohne die neue Nachricht
     * @param text        die neue Nachricht, so wie sie eingetippt wurde
     * @param nutrition   Ziele und Essen der Person, die fragt, als JSON-Objekt aus dem
     *                    Kalorienzaehler - nur ihre, nie die einer anderen; {@code null}
     *                    ohne Healthy
     * @throws org.springframework.web.server.ResponseStatusException mit einer Meldung
     *         fuer die Oberflaeche, wenn keine Antwort zustande kommt
     */
    RecipeAnswer answer(List<String> recipeNames, List<ChatMessage> history, String text, String nutrition);

    /** Ohne Zusammenhang aus Healthy. */
    default RecipeAnswer answer(List<String> recipeNames, List<ChatMessage> history, String text) {
        return answer(recipeNames, history, text, null);
    }
}
