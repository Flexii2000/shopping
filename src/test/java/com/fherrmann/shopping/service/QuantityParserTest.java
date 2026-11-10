package com.fherrmann.shopping.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mengen im Namen erkennen - und dort lassen, wo keine gemeint ist. */
class QuantityParserTest {

    private static QuantityParser.Split split(String name) {
        return QuantityParser.split(name);
    }

    @Test
    void liestDieMengeAusDemNamen() {
        assertEquals(new QuantityParser.Split("gemischtes Hack", "200 g"), split("gemischtes Hack 200g"));
        assertEquals(new QuantityParser.Split("Hack", "200 g"), split("Hack 200 Gramm"));
        assertEquals(new QuantityParser.Split("Zwiebeln", "2 Stk"), split("2 Zwiebeln"));
        assertEquals(new QuantityParser.Split("Zwiebeln", "2 Stk"), split("zwei Zwiebeln"));
        assertEquals(new QuantityParser.Split("Klopapier", "2 Stk"), split("Klopapier 2"));
        assertEquals(new QuantityParser.Split("Klopapier", "2 Stk"), split("Klopapier 2 stk"));
        assertEquals(new QuantityParser.Split("Klopapier", "2 Stk"), split("Klopapier zwei"));
        assertEquals(new QuantityParser.Split("Milch", "1 l"), split("Milch 1l"));
        assertEquals(new QuantityParser.Split("Milch", "1 l"), split("ein Liter Milch"));
        assertEquals(new QuantityParser.Split("Butter", "2 Stk"), split("2 Stück Butter"));
        assertEquals(new QuantityParser.Split("Nudeln", "1 Pck"), split("eine Packung Nudeln"));
        assertEquals(new QuantityParser.Split("Hack", "0,5 kg"), split("ein halbes Kilo Hack"));
        assertEquals(new QuantityParser.Split("Tomaten", "3 Dosen"), split("Tomaten 3 Dosen"));
        assertEquals(new QuantityParser.Split("Bier", "1 Kasten"), split("Bier 1 Kasten"));
        assertEquals(new QuantityParser.Split("Cola", "1,5 l"), split("Cola 1,5 l"));
        assertEquals(new QuantityParser.Split("Toast", "2 Stk"), split("2x Toast"));
        assertEquals(new QuantityParser.Split("gemischte Brote", "2 Stk"), split("2 gemischte Brote"));
    }

    @Test
    void einEinheitenwortOhneZahlHeisstEins() {
        assertEquals(new QuantityParser.Split("Cola", "1 l"), split("Liter Cola"));
        assertEquals(new QuantityParser.Split("Nudeln", "1 Pck"), split("Packung Nudeln"));
        assertEquals(new QuantityParser.Split("Tomaten", "1 Dose"), split("Dose Tomaten"));
        assertEquals(new QuantityParser.Split("Cola", "1 l"), split("Cola Liter"));
        assertEquals("1 l", QuantityParser.normalize("Liter"));
        assertEquals(new QuantityParser.Split("g Zucker", null), split("g Zucker"));
        assertEquals(new QuantityParser.Split("Glas", null), split("Glas"));
    }

    @Test
    void mengenWerdenZusammengefasst() {
        assertEquals("1000 g", QuantityParser.merge("500 g", "500 g"));
        assertEquals("5 Stk", QuantityParser.merge("2 Stk", "3 Stk"));
        assertEquals("2,5 kg", QuantityParser.merge("1,5 kg", "1 kg"));
        assertEquals("3 Dosen", QuantityParser.merge("1 Dose", "2 Dosen"));
        assertEquals("2 Stk", QuantityParser.merge(null, "2 Stk"));
        assertEquals("500 g", QuantityParser.merge("500 g", "  "));
        assertEquals("500 g + 1 Pck", QuantityParser.merge("500 g", "1 Pck"));
        assertEquals("eine Handvoll", QuantityParser.merge("eine Handvoll", "eine Handvoll"));
        assertNull(QuantityParser.merge(null, null));
    }

    @Test
    void laesstNamenOhneMengeInRuhe() {
        assertEquals(new QuantityParser.Split("Mehl Type 405", null), split("Mehl Type 405"));
        assertEquals(new QuantityParser.Split("H-Milch 3,5%", null), split("H-Milch 3,5%"));
        assertEquals(new QuantityParser.Split("Milch 1,5", null), split("Milch 1,5"));
        assertEquals(new QuantityParser.Split("Eier 10er", null), split("Eier 10er"));
        assertEquals(new QuantityParser.Split("ein paar Äpfel", null), split("ein paar Äpfel"));
        assertEquals(new QuantityParser.Split("Bananen", null), split("Bananen"));
    }

    @Test
    void angegebeneMengenWerdenNurVereinheitlicht() {
        assertEquals(new QuantityParser.Split("Hack 200g", "500 g"), QuantityParser.resolve("Hack 200g", "500g"));
        assertEquals("2 Stk", QuantityParser.normalize("2"));
        assertEquals("3 Dosen", QuantityParser.normalize("drei Dosen"));
        assertEquals("1 Dose", QuantityParser.normalize("1 Dose"));
        assertEquals("200 g", QuantityParser.normalize("200 Gramm"));
        assertEquals("eine Handvoll", QuantityParser.normalize("eine Handvoll"));
        assertNull(QuantityParser.resolve("Bananen", "  ").quantity());
    }

    // MARK: - Umrechnen auf eine andere Portionenzahl

    /**
     * Dieselben Beispiele rechnet die App nach ({@code ShoppingQuantity.scale} in
     * cockpit-ios, {@code Tests/ShoppingQuantityTests.swift}) - die Datei ist die
     * gemeinsame Wahrheit ueber die Rundung.
     */
    @Test
    void rechnetDieGemeinsamenBeispieleUm() throws Exception {
        JsonNode vectors;
        try (InputStream in = getClass().getResourceAsStream("/scale-vectors.json")) {
            vectors = JsonMapper.builder().build().readTree(in).path("vectors");
        }
        assertTrue(vectors.size() >= 30, "die Beispiele fehlen");
        for (JsonNode vector : vectors) {
            String quantity = vector.path("quantity").isNull() ? null : vector.path("quantity").asString();
            String expected = vector.path("expected").isNull() ? null : vector.path("expected").asString();
            QuantityParser.Scaled scaled = QuantityParser.scale(quantity,
                    vector.path("portions").asInt(), vector.path("base").asInt());
            assertEquals(expected, scaled.quantity(), "Menge: " + vector);
            assertEquals(vector.path("scaled").asBoolean(), scaled.scaled(), "scaled: " + vector);
        }
    }

    @Test
    void rechnetJedenTeilEinerZusammengefasstenMengeEinzeln() {
        // So schreibt merge zwei Einheiten nebeneinander - und so liest scale sie wieder.
        String merged = QuantityParser.merge("500 g", "1 Pck");
        assertEquals("250 g + 0,5 Pck", QuantityParser.scale(merged, 1, 2).quantity());
        // Was auf der Liste zusammengezaehlt wird, bleibt ehrlich: kein Aufrunden auf ganze Packungen.
        assertEquals("1,5 Pck", QuantityParser.scale("1 Pck", 3, 2).quantity());
    }

    @Test
    void wasSichNichtLesenLaesstBleibtUndSagtDas() {
        QuantityParser.Scaled handful = QuantityParser.scale("eine Handvoll", 6, 2);
        assertEquals("eine Handvoll", handful.quantity());
        assertFalse(handful.scaled());
        assertFalse(QuantityParser.scale("nach Geschmack", 3, 2).scaled());
        assertFalse(QuantityParser.scale("1 1/2 Stk", 3, 2).scaled());
        // Ohne Menge gibt es nichts umzurechnen - und nichts, was blass dastehen muesste.
        assertTrue(QuantityParser.scale(null, 3, 2).scaled());
        assertNull(QuantityParser.scale("  ", 3, 2).quantity());
    }

    @Test
    void setztZahlUndEinheitDesAgentenZusammen() {
        assertEquals("250 g", QuantityParser.compose(new BigDecimal("250"), "g"));
        assertEquals("250 g", QuantityParser.compose(new BigDecimal("250.0"), "Gramm"));
        assertEquals("1,5 l", QuantityParser.compose(new BigDecimal("1.5"), "l"));
        assertEquals("2 EL", QuantityParser.compose(new BigDecimal("2"), "EL"));
        assertEquals("1 Dose", QuantityParser.compose(BigDecimal.ONE, "Dose"));
        assertEquals("2 Dosen", QuantityParser.compose(new BigDecimal("2"), "Dosen"));
        assertEquals("3 Stk", QuantityParser.compose(new BigDecimal("3"), null));
        assertEquals("3 Stk", QuantityParser.compose(new BigDecimal("3"), " "));
        // Eine Einheit, die die Tabelle nicht kennt, bleibt Text: sichtbar, aber nicht umrechenbar.
        assertEquals("2 Zehen", QuantityParser.compose(new BigDecimal("2"), "Zehen"));
        assertFalse(QuantityParser.scale("2 Zehen", 4, 2).scaled());
        assertNull(QuantityParser.compose(null, null));
        assertNull(QuantityParser.compose(BigDecimal.ZERO, ""));
        assertEquals("1 Prise", QuantityParser.compose(null, "Prise"));
    }

    @Test
    void nenntDieEinheitenInDerGespeichertenSchreibweise() {
        List<String> units = QuantityParser.units();
        assertEquals("Stk", units.get(0));
        assertTrue(units.containsAll(List.of("g", "kg", "ml", "l", "EL", "TL", "Pck", "Dose", "Prise", "Bund")), units.toString());
        assertEquals(units.size(), units.stream().distinct().count());
        // Jede laesst sich lesen, so wie sie dasteht - sonst koennte der Agent sie nicht benutzen.
        for (String unit : units) {
            String text = QuantityParser.compose(new BigDecimal("3"), unit);
            assertEquals(text, QuantityParser.normalize(text), unit);
            assertTrue(QuantityParser.scale(text, 4, 2).scaled(), unit);
        }
    }
}
