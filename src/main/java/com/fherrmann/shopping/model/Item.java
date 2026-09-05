package com.fherrmann.shopping.model;

import java.time.Instant;

/**
 * Ein Eintrag auf der Liste.
 *
 * @param quantity  freier Text ("2", "500 g"), oder {@code null}
 * @param note      woher der Eintrag kommt - bei Zutaten der Name des Gerichts
 * @param category  der Schluessel einer {@link Category}; in aelteren Dateien
 *                  {@code null}, nach aussen dann "other"
 * @param addedBy   der Name hinter dem Token, oder "regel" bei einem
 *                  automatisch gesetzten Eintrag
 * @param checkedAt wann abgehakt, oder {@code null}. Abgehaktes bleibt bis
 *                  Mitternacht durchgestrichen sichtbar und faellt dann aus
 *                  der Liste; in der Datei bleibt es noch eine Weile.
 * @param dishId    gesetzt, wenn der Eintrag aus einem Gericht stammt
 * @param ruleId    gesetzt, wenn ihn eine Regel gesetzt hat - abhaken schiebt
 *                  die Regel dann um ihren Rhythmus weiter
 */
public record Item(
        String id,
        String name,
        String quantity,
        String note,
        String category,
        Instant addedAt,
        String addedBy,
        Instant checkedAt,
        String checkedBy,
        String dishId,
        String ruleId) {

    public Item {
        category = Category.orOther(category).key();
    }

    public Item withChecked(Instant checkedAt, String checkedBy) {
        return new Item(id, name, quantity, note, category, addedAt, addedBy, checkedAt, checkedBy, dishId, ruleId);
    }

    public Item withText(String name, String quantity, String note, String category) {
        return new Item(id, name, quantity, note, category, addedAt, addedBy, checkedAt, checkedBy, dishId, ruleId);
    }

    /**
     * Zusammengefasst mit einem zweiten Eintrag desselben Namens: Menge und
     * Notiz vereint, eine Herkunft (Gericht, Regel) kommt dazu, wenn noch
     * keine da ist. Name, Schreibweise und Zeitpunkt des ersten bleiben.
     */
    public Item merged(String quantity, String note, String dishId, String ruleId) {
        return new Item(id, name, quantity, note, category, addedAt, addedBy, checkedAt, checkedBy,
                this.dishId != null ? this.dishId : dishId, this.ruleId != null ? this.ruleId : ruleId);
    }
}
