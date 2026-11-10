package com.fherrmann.shopping.healthy;

import java.util.List;
import java.util.Optional;

/**
 * Der Kalorienzaehler von Healthy, so weit die Einkaufsliste ihn braucht: ein
 * gekochtes Rezept dort als Gericht anlegen, bei jeder Person, die es dort gibt - und
 * fuer den Rezept-Chat Ziele und Essen der einen Person holen, die gerade fragt.
 *
 * <p>Eigene Schnittstelle, damit "Kochen" nichts vom HTTP dahinter weiss und Tests
 * eine feste Antwort einsetzen koennen.
 */
public interface Healthy {

    /** Ob dieser Server den Weg zum Kalorienzaehler kennt (ein Dienst-Token ist gesetzt). */
    boolean isConfigured();

    /**
     * Legt das Gericht bei den genannten Personen an oder bringt es auf den neuen Stand.
     *
     * @param persons die Namen aus der Einkaufsliste - wer davon kein Healthy hat, kommt
     *                als {@code not-linked} zurueck
     * @throws HealthyException wenn der Kalorienzaehler nicht erreichbar ist oder nicht
     *                          verwertbar antwortet - dann hat niemand das Gericht bekommen
     */
    List<Outcome> writeDish(List<String> persons, HealthyDish dish);

    /**
     * Ziele, heute und die letzten zwei Wochen <b>dieser einen</b> Person als kompaktes
     * JSON-Objekt (Healthy-Vertrag §11.3, {@code /api/internal/recipe-context}) - fuer
     * ihren Rezept-Chat und nur fuer ihn. Leer, wenn sie kein Healthy hat.
     *
     * @throws HealthyException wenn der Kalorienzaehler nicht erreichbar ist oder nicht
     *                          verwertbar antwortet
     */
    default Optional<String> context(String person) {
        return Optional.empty();
    }

    /**
     * @param status  {@code created}, {@code updated}, {@code unchanged},
     *                {@code not-linked} oder {@code rejected}
     * @param message bei {@code rejected} der Grund, sonst {@code null}
     */
    record Outcome(String person, String status, String message) {

        public static final String NOT_LINKED = "not-linked";
    }
}
