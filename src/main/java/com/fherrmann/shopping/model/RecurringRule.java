package com.fherrmann.shopping.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * "Klopapier alle 14 Tage": setzt sich selbst auf die Liste.
 *
 * @param everyDays der Rhythmus, mindestens 1
 * @param nextAt    wann der naechste Eintrag faellig ist. Wird beim Setzen
 *                  weitergeschoben - und beim Abhaken des Eintrags, damit
 *                  der Rhythmus ab dem Kauf zaehlt und nicht ab dem Plan.
 */
public record RecurringRule(
        String id,
        String name,
        String quantity,
        int everyDays,
        LocalDate nextAt,
        Instant createdAt) {

    public RecurringRule withNextAt(LocalDate nextAt) {
        return new RecurringRule(id, name, quantity, everyDays, nextAt, createdAt);
    }

    public RecurringRule withText(String name, String quantity, int everyDays, LocalDate nextAt) {
        return new RecurringRule(id, name, quantity, everyDays, nextAt, createdAt);
    }
}
