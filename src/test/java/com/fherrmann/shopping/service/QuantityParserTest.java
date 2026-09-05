package com.fherrmann.shopping.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Mengen im Namen erkennen - und dort lassen, wo keine gemeint ist. */
class QuantityParserTest {

    @Test
    void liestDieMengeAusDemNamen() {
        assertEquals(new QuantityParser.Split("gemischtes Hack", "200 g"), QuantityParser.split("gemischtes Hack 200g"));
        assertEquals(new QuantityParser.Split("Zwiebeln", "2 Stk"), QuantityParser.split("2 Zwiebeln"));
        assertEquals(new QuantityParser.Split("Milch", "1 l"), QuantityParser.split("Milch 1l"));
        assertEquals(new QuantityParser.Split("Butter", "2 Stk"), QuantityParser.split("2 Stück Butter"));
        assertEquals(new QuantityParser.Split("Cola", "1,5 l"), QuantityParser.split("Cola 1,5 l"));
        assertEquals(new QuantityParser.Split("Toast", "2 Stk"), QuantityParser.split("2x Toast"));
        assertEquals(new QuantityParser.Split("Nudeln", "1 Pck"), QuantityParser.split("Nudeln 1 Packung"));
    }

    @Test
    void laesstNamenOhneMengeInRuhe() {
        assertEquals(new QuantityParser.Split("Mehl Type 405", null), QuantityParser.split("Mehl Type 405"));
        assertEquals(new QuantityParser.Split("H-Milch 3,5%", null), QuantityParser.split("H-Milch 3,5%"));
        assertEquals(new QuantityParser.Split("Eier 10er", null), QuantityParser.split("Eier 10er"));
        assertEquals(new QuantityParser.Split("gemischte Brote", "2 Stk"), QuantityParser.split("2 gemischte Brote"));
        assertEquals(new QuantityParser.Split("Bananen", null), QuantityParser.split("Bananen"));
    }

    @Test
    void angegebeneMengenWerdenNurVereinheitlicht() {
        assertEquals(new QuantityParser.Split("Hack 200g", "500 g"), QuantityParser.resolve("Hack 200g", "500g"));
        assertEquals("2 Stk", QuantityParser.normalize("2"));
        assertEquals("eine Handvoll", QuantityParser.normalize("eine Handvoll"));
        assertNull(QuantityParser.resolve("Bananen", "  ").quantity());
    }
}
