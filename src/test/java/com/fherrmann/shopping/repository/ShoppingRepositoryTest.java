package com.fherrmann.shopping.repository;

import com.fherrmann.shopping.model.Dish;
import com.fherrmann.shopping.model.Ingredient;
import com.fherrmann.shopping.model.Item;
import com.fherrmann.shopping.model.Nutrition;
import com.fherrmann.shopping.model.RecurringRule;
import com.fherrmann.shopping.model.ShoppingData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShoppingRepositoryTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");

    @TempDir
    Path dir;

    @Test
    void ohneDateiFaengtAllesLeerAn() {
        ShoppingRepository repository = new ShoppingRepository(dir.resolve("shopping.json").toString(), MAPPER);
        assertEquals(ShoppingData.empty(), repository.load());
    }

    @Test
    void speichernUndWiederLesen() throws Exception {
        Path file = dir.resolve("shopping.json");
        ShoppingRepository repository = new ShoppingRepository(file.toString(), MAPPER);
        ShoppingData data = new ShoppingData(
                List.of(new Item("i1", "Milch", "2", null, "dairy", NOW, "felix", null, null, null, null),
                        new Item("i2", "Zwiebeln", "500 g", "Bolognese", "produce", NOW, "joana", NOW, "felix", "d1", null),
                        new Item("i3", "Klopapier", null, null, "household", NOW, "regel", null, null, null, "r1")),
                List.of(new Dish("d1", "Bolognese", List.of(new Ingredient("Zwiebeln", "500 g")), NOW)),
                List.of(new RecurringRule("r1", "Klopapier", null, 14, LocalDate.of(2026, 9, 17), NOW)),
                Map.of("kokosmilch", "dairy"));
        repository.save(data);
        assertEquals(data, repository.load());
        // Zeitpunkte als ISO-Text mit Z, Datum als yyyy-MM-dd - so liest es die App.
        String json = Files.readString(file);
        assertTrue(json.contains("\"2026-09-03T10:00:00Z\""), json);
        assertTrue(json.contains("\"2026-09-17\""), json);
    }

    /** Seit 12.10. mit Kennungen der Apps - eine Datei von vorher liest sich ohne. */
    @Test
    void aeltereDateiOhneKennungenDerApps() throws Exception {
        Path file = dir.resolve("shopping.json");
        Files.writeString(file, """
                {"items":[{"id":"i1","name":"Milch","category":"dairy","addedAt":"2026-09-03T10:00:00Z","addedBy":"felix"}],
                 "dishes":[],"recurring":[],"learned":{}}
                """);
        ShoppingData data = new ShoppingRepository(file.toString(), MAPPER).load();
        assertEquals(1, data.items().size());
        assertTrue(data.clientIds().isEmpty());
    }

    @Test
    void aeltereDateiOhneKategorieLiestSichAlsSonstiges() throws Exception {
        Path file = dir.resolve("shopping.json");
        Files.writeString(file, """
                {"items":[{"id":"i1","name":"Milch","addedAt":"2026-09-03T10:00:00Z","addedBy":"felix"}],
                 "dishes":[],"recurring":[]}
                """);
        ShoppingData data = new ShoppingRepository(file.toString(), MAPPER).load();
        assertEquals("other", data.items().get(0).category());
        assertEquals(Map.of(), data.learned());
    }

    /**
     * Die Datei, wie der Dienst sie am 10.11.2026 geschrieben hat - vor den Rezepten.
     * Sie muss sich weiter lesen lassen: ein einziges primitives Feld an Rezept oder
     * Zutat ({@code boolean pantry}, {@code int portions}) liesse Jackson 3 hier
     * scheitern, und die Einkaufsliste auf dem Server waere nach dem Deploy weg.
     */
    @Test
    void dieDateiVonVorDenRezeptenLiestSichUnveraendert() throws Exception {
        Path file = dir.resolve("shopping.json");
        try (InputStream in = getClass().getResourceAsStream("/shopping-before-recipes.json")) {
            Files.write(file, in.readAllBytes());
        }
        ShoppingRepository repository = new ShoppingRepository(file.toString(), MAPPER);
        ShoppingData data = repository.load();

        assertEquals(6, data.items().size());
        assertEquals(2, data.dishes().size());
        Dish bolognese = data.dishes().get(0);
        assertEquals("Bolognese", bolognese.name());
        assertEquals(List.of(new Ingredient("Hackfleisch", "500 g"), new Ingredient("Zwiebeln", "2 Stk"),
                new Ingredient("Salz", null)), bolognese.ingredients());
        assertFalse(bolognese.ingredients().get(0).pantry(), "ohne Feld heisst: kein Vorrat");
        assertNull(bolognese.portions());
        assertNull(bolognese.minutes());
        assertEquals(List.of(), bolognese.steps());
        assertNull(bolognese.nutrition());
        assertNull(bolognese.gramsPerPortion());
        assertNull(bolognese.source());
        assertNull(bolognese.createdBy());
        assertEquals(1, data.recurring().size());
        assertEquals(Map.of("kokosmilch", "dairy"), data.learned());
        assertEquals(1, data.clientIds().size());

        // Und einmal geschrieben und wieder gelesen ist es derselbe Bestand.
        repository.save(data);
        assertEquals(data, repository.load());
    }

    @Test
    void einRezeptMitAllemSpeichernUndWiederLesen() throws Exception {
        Path file = dir.resolve("shopping.json");
        ShoppingRepository repository = new ShoppingRepository(file.toString(), MAPPER);
        Dish recipe = new Dish("d1", "Pasta mit Feta",
                List.of(new Ingredient("Penne", "250 g", false), new Ingredient("Olivenöl", "2 EL", true)), NOW,
                2, 25, List.of("Wasser aufsetzen.", "Penne kochen."), new Nutrition(640.0, 24.0, 78.0, 24.0),
                420.0, "chat", "joana");
        ShoppingData data = new ShoppingData(List.of(), List.of(recipe), List.of(), Map.of());
        repository.save(data);
        assertEquals(data, repository.load());
        String json = Files.readString(file);
        assertTrue(json.contains("\"pantry\" : true"), json);
        assertTrue(json.contains("\"gramsPerPortion\" : 420.0"), json);
        assertTrue(json.contains("\"source\" : \"chat\""), json);
        // Keine Hilfsmethode ist als Feld in die Datei geraten.
        JsonNodeKeys.assertKeys(MAPPER.readTree(json).path("dishes").get(0),
                "id", "name", "ingredients", "createdAt", "portions", "minutes", "steps", "nutrition",
                "gramsPerPortion", "source", "createdBy");
    }

    /** Vergleicht die Schluessel eines Objekts - ein {@code isX()} am Record faellt so auf. */
    private static final class JsonNodeKeys {
        static void assertKeys(tools.jackson.databind.JsonNode node, String... expected) {
            List<String> keys = new java.util.ArrayList<>(node.propertyNames());
            assertEquals(List.of(expected), keys);
        }
    }
}
