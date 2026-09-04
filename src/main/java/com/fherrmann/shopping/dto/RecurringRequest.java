package com.fherrmann.shopping.dto;

import java.time.LocalDate;

/**
 * Anlegen und Aendern einer Regel.
 *
 * @param nextAt beim Anlegen optional - ohne Angabe heute, der Eintrag
 *               erscheint also sofort. Beim Aendern ebenso: ohne Angabe
 *               bleibt der bisherige Termin.
 */
public record RecurringRequest(String name, String quantity, Integer everyDays, LocalDate nextAt) {
}
