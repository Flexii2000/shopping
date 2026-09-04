package com.fherrmann.shopping.repository;

import com.fherrmann.shopping.model.Dish;
import com.fherrmann.shopping.model.Ingredient;
import com.fherrmann.shopping.model.Item;
import com.fherrmann.shopping.model.RecurringRule;
import com.fherrmann.shopping.model.ShoppingData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                        new Item("i2", "Zwiebeln", "500 g", "Bolognese", "produce", NOW, "freundin", NOW, "felix", "d1", null),
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
}
