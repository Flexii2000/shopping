package com.fherrmann.shopping.service;

import com.fherrmann.shopping.model.Category;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategorizerTest {

    private final Categorizer categorizer = new Categorizer();

    private Category of(String name) {
        return categorizer.classify(name, Map.of());
    }

    @Test
    void dasWoerterbuchIstGrossGenug() {
        assertTrue(categorizer.dictionarySize() > 800, "Eintraege: " + categorizer.dictionarySize());
    }

    @Test
    void normalisierenNimmtMengenUndFuellwoerterWeg() {
        assertEquals("zwiebeln", Categorizer.normalize("2 Zwiebeln"));
        assertEquals("milch", Categorizer.normalize("Milch 1l"));
        assertEquals("milch", Categorizer.normalize("Milch, 1,5 l"));
        assertEquals("hackfleisch", Categorizer.normalize("500g Hackfleisch"));
        assertEquals("eier", Categorizer.normalize("Eier 10er"));
        assertEquals("aepfel", Categorizer.normalize("Bio-Äpfel"));
        assertEquals("tk pizza", Categorizer.normalize("TK-Pizza"));
        assertEquals("creme fraiche", Categorizer.normalize("Crème fraîche"));
        assertEquals("kaese", Categorizer.normalize("KÄSE"));
        // Nur eine Zahl: bleibt, statt zu verschwinden.
        assertEquals("2", Categorizer.normalize("2"));
    }

    @Test
    void dieUeblichenVerdaechtigen() {
        assertEquals(Category.PRODUCE, of("Tomaten"));
        assertEquals(Category.PRODUCE, of("2 Zwiebeln"));
        assertEquals(Category.PRODUCE, of("Äpfel"));
        assertEquals(Category.PRODUCE, of("apfel"));
        assertEquals(Category.PRODUCE, of("Rote Paprika"));
        assertEquals(Category.BAKERY, of("Brötchen"));
        assertEquals(Category.BAKERY, of("Vollkornbrot"));
        assertEquals(Category.MEAT, of("500g Hackfleisch"));
        assertEquals(Category.MEAT, of("Hähnchenbrust"));
        assertEquals(Category.DAIRY, of("Milch 1l"));
        assertEquals(Category.DAIRY, of("MILCH"));
        assertEquals(Category.DAIRY, of("Gouda"));
        assertEquals(Category.DAIRY, of("Eier"));
        assertEquals(Category.CANNED, of("Tomaten passiert"));
        assertEquals(Category.CANNED, of("Thunfisch Dose"));
        assertEquals(Category.STAPLES, of("Spaghetti"));
        assertEquals(Category.STAPLES, of("Reis"));
        assertEquals(Category.STAPLES, of("Olivenöl"));
        assertEquals(Category.SPICES, of("Ketchup"));
        assertEquals(Category.SPICES, of("Sojasauce"));
        assertEquals(Category.SNACKS, of("Chips"));
        assertEquals(Category.SNACKS, of("Gummibärchen"));
        assertEquals(Category.BEVERAGES, of("Cola Zero"));
        assertEquals(Category.BEVERAGES, of("Apfelsaft"));
        assertEquals(Category.BEVERAGES, of("Kaffee"));
        assertEquals(Category.FROZEN, of("Eis"));
        assertEquals(Category.FROZEN, of("Fischstäbchen"));
        assertEquals(Category.DRUGSTORE, of("Zahnpasta"));
        assertEquals(Category.DRUGSTORE, of("Shampoo"));
        assertEquals(Category.HOUSEHOLD, of("Klopapier"));
        assertEquals(Category.HOUSEHOLD, of("Spülmittel"));
        assertEquals(Category.OTHER, of("Geschenk für Oma"));
    }

    @Test
    void dieFallen() {
        // "milch" steckt drin, gehoert aber nicht zu den Milchprodukten.
        assertEquals(Category.STAPLES, of("Kokosmilch"));
        assertEquals(Category.STAPLES, of("Milchreis"));
        assertEquals(Category.SNACKS, of("Milchschnitte"));
        // Pflanzendrinks stehen im Kuehlregal bei der Milch.
        assertEquals(Category.DAIRY, of("Hafermilch"));
        assertEquals(Category.DAIRY, of("Sojamilch"));
        // "eis" am Ende von Reis ist kein Eis.
        assertEquals(Category.STAPLES, of("Basmatireis"));
        // Tiefkuehl schlaegt das Woerterbuch.
        assertEquals(Category.FROZEN, of("TK-Erbsen"));
        assertEquals(Category.FROZEN, of("Tiefkühlspinat"));
        assertEquals(Category.PRODUCE, of("Erbsen"));
        // Komposita ueber die Endung.
        assertEquals(Category.DAIRY, of("Ziegenfrischkäse"));
        assertEquals(Category.BEVERAGES, of("Rhabarbersaft"));
        assertEquals(Category.BAKERY, of("Kürbiskernbrot"));
        assertEquals(Category.MEAT, of("Krakauer Wurst"));
        assertEquals(Category.CANNED, of("Tomatensuppe"));
        assertEquals(Category.BAKERY, of("Apfelkuchen"));
        assertEquals(Category.BEVERAGES, of("Kaffee"));
        assertEquals(Category.BEVERAGES, of("Kakao"));
        assertEquals(Category.OTHER, of("Briefmarken"));
        assertEquals(Category.PRODUCE, of("Rhabarberstangen"));
        assertEquals(Category.MEAT, of("Hähnchenschenkel"));
    }

    @Test
    void gelerntesSchlaegtDasWoerterbuch() {
        Map<String, String> learned = Map.of("kokosmilch", "dairy", "tomate", "canned");
        assertEquals(Category.DAIRY, categorizer.classify("Kokosmilch", learned));
        assertEquals(Category.DAIRY, categorizer.classify("2 Dosen Kokosmilch", learned));
        // Gelernt fuer die Einzahl, gefragt in der Mehrzahl.
        assertEquals(Category.CANNED, categorizer.classify("Tomaten", learned));
        // Ein kaputter Schluessel in der Datei faellt aufs Woerterbuch zurueck.
        assertEquals(Category.PRODUCE, categorizer.classify("Gurke", Map.of("gurke", "unsinn")));
    }
}
