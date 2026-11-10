package com.fherrmann.shopping.recipe;

import java.time.Instant;

/**
 * Eine Nachricht im Rezept-Chat - so liegt sie in der Datei, und so geht sie an die
 * Clients.
 *
 * @param id          bei der eigenen Nachricht die Kennung der App (kleingeschriebene
 *                    UUID), sonst vom Dienst vergeben
 * @param role        {@code "user"} oder {@code "assistant"}
 * @param draft       der Rezeptvorschlag an einer Antwort, sonst {@code null}
 * @param savedDishId das Rezept, das aus dem Entwurf gespeichert wurde, sonst {@code null}
 */
public record ChatMessage(String id, String role, String text, Instant at, RecipeDraft draft, String savedDishId) {

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    public ChatMessage withSavedDishId(String dishId) {
        return new ChatMessage(id, role, text, at, draft, dishId);
    }
}
