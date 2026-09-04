package com.fherrmann.shopping.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sieht regelmaessig nach, ob eine Regel etwas auf die Liste setzen soll -
 * und raeumt nachts alte abgehakte Eintraege aus der Datei.
 *
 * <p>Der Lauf ist absichtlich grob (stuendlich, plus beim Start): eine Regel
 * "alle 14 Tage" braucht keine Minutengenauigkeit, und nach jedem Anlegen
 * einer Regel sieht der Dienst ohnehin sofort nach.
 */
@Component
public class RecurringScheduler {

    private final ShoppingService service;

    public RecurringScheduler(ShoppingService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${shopping.recurring.check-interval:3600000}", initialDelay = 5000)
    public void run() {
        service.runRecurring();
    }

    @Scheduled(cron = "0 15 3 * * *", zone = "${shopping.zone:Europe/Berlin}")
    public void cleanup() {
        service.cleanup();
    }
}
