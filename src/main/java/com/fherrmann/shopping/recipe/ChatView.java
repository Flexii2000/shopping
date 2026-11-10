package com.fherrmann.shopping.recipe;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Der Rezept-Chat einer Person: das Gespraech und ob gerade eine Antwort entsteht.
 * Derselbe Aufruf ist auch die Nachfrage - die Clients holen ihn alle zwei Sekunden,
 * solange {@code job.status} {@code "running"} ist.
 *
 * @param available ob die Person den Chat benutzen kann (freigeschaltet und der Agent
 *                  eingerichtet); sonst zeigen die Clients ihn gar nicht an
 * @param job       {@code null}, wenn nichts laeuft und nichts scheiterte
 */
public record ChatView(boolean available, List<ChatMessage> messages, JobView job) {

    /**
     * @param status         {@code "running"} oder {@code "failed"}
     * @param elapsedSeconds nur bei {@code running}
     * @param error          nur bei {@code failed}: ein Satz fuer die Oberflaeche
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JobView(String status, Long elapsedSeconds, String error) {

        public static final String RUNNING = "running";
        public static final String FAILED = "failed";
    }
}
