package com.fherrmann.shopping.dto;

import java.util.List;

/**
 * Was "Kochen" im Kalorienzaehler bewirkt hat. Kommt auch dann mit 200, wenn es bei
 * einer Person nicht geklappt hat - kochen kann man trotzdem.
 *
 * @param reason  {@code null}, wenn der Kalorienzaehler gefragt wurde; sonst warum
 *                nicht: {@code "no-nutrition"} (das Rezept hat keine Naehrwerte) oder
 *                {@code "not-configured"} (dieser Server kennt den Weg dorthin nicht)
 * @param healthy je Person mit Healthy-Zugang, was aus dem Gericht wurde. Wer die
 *                Einkaufsliste benutzt, aber kein Healthy hat, steht nicht in der Liste.
 */
public record CookResult(String dishId, String reason, List<PersonResult> healthy) {

    public static final String NO_NUTRITION = "no-nutrition";
    public static final String NOT_CONFIGURED = "not-configured";

    /**
     * @param status  {@code created}, {@code updated}, {@code unchanged},
     *                {@code rejected} (der Kalorienzaehler nimmt das Gericht nicht) oder
     *                {@code failed} (nicht erreichbar)
     * @param message ein Satz dazu bei {@code rejected} und {@code failed}, sonst {@code null}
     */
    public record PersonResult(String person, String status, String message) {

        public static final String FAILED = "failed";
    }
}
