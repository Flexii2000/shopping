package com.fherrmann.shopping.service;

import com.fherrmann.shopping.dto.Board;
import com.fherrmann.shopping.dto.DishRequest;
import com.fherrmann.shopping.dto.ItemRequest;
import com.fherrmann.shopping.dto.RecurringRequest;
import com.fherrmann.shopping.model.Item;
import com.fherrmann.shopping.model.ShoppingData;
import com.fherrmann.shopping.repository.ShoppingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShoppingServiceTest {

    /** Ein Donnerstagmittag in Berlin (14:00 MESZ). */
    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private final AtomicReference<ShoppingData> stored = new AtomicReference<>(ShoppingData.empty());
    private ShoppingService service;

    @BeforeEach
    void setUp() {
        service = at(NOW);
    }

    /** Derselbe Bestand, gesehen zu einem anderen Zeitpunkt. */
    private ShoppingService at(Instant now) {
        ShoppingRepository repository = mock(ShoppingRepository.class);
        when(repository.load()).thenAnswer(inv -> stored.get());
        doAnswer(inv -> { stored.set(inv.getArgument(0)); return null; }).when(repository).save(any());
        return new ShoppingService(repository, new Categorizer(), Clock.fixed(now, BERLIN), 90);
    }

    @Test
    void abgehaktBleibtBisMitternachtSichtbarUndDannNurInDerDatei() {
        Board board = service.createItem("felix", new ItemRequest("Milch", "2", null, null));
        assertEquals("felix", board.me());
        Item milch = board.items().get(0);
        assertEquals("felix", milch.addedBy());
        assertNull(milch.checkedAt());

        board = service.check("freundin", milch.id());
        assertEquals("freundin", board.items().get(0).checkedBy());
        assertEquals(NOW, board.items().get(0).checkedAt());

        // 23:59 in Berlin: noch da.
        Board lateEvening = at(Instant.parse("2026-09-03T21:59:00Z")).board("felix");
        assertEquals(1, lateEvening.items().size());
        // 00:01 am naechsten Tag: weg vom Brett, nicht aus der Datei.
        Board nextDay = at(Instant.parse("2026-09-03T22:01:00Z")).board("felix");
        assertTrue(nextDay.items().isEmpty());
        assertEquals(1, stored.get().items().size());
    }

    @Test
    void abhakenIstIdempotentUndDerErsteZaehlt() {
        String id = service.createItem("felix", new ItemRequest("Brot", null, null, null)).items().get(0).id();
        service.check("felix", id);
        Board again = at(NOW.plus(Duration.ofHours(1))).check("freundin", id);
        assertEquals("felix", again.items().get(0).checkedBy(), "der zweite Haken aendert nichts");
        assertEquals(NOW, again.items().get(0).checkedAt());

        Board reopened = service.uncheck("felix", id);
        assertNull(reopened.items().get(0).checkedAt());
        assertNull(reopened.items().get(0).checkedBy());
        // Haken zurueck auf einem offenen Eintrag: nichts passiert.
        assertNull(service.uncheck("felix", id).items().get(0).checkedAt());
    }

    @Test
    void offeneZuerstDannAbgehakte() {
        // Drei Dinge ohne Kategorie - damit hier nur die Reihenfolge zaehlt.
        String a = service.createItem("felix", new ItemRequest("Zeitung", null, null, null)).items().get(0).id();
        service.createItem("felix", new ItemRequest("Briefmarken", null, null, null));
        at(NOW.plus(Duration.ofMinutes(1))).createItem("felix", new ItemRequest("Geschenk", null, null, null));
        Board board = service.check("felix", a);
        assertEquals(List.of("Briefmarken", "Geschenk", "Zeitung"), board.items().stream().map(Item::name).toList());
    }

    @Test
    void offeneNachKategorieImRundgangDannNachAlter() {
        service.createItem("felix", new ItemRequest("Klopapier", null, null, null));
        service.createItem("felix", new ItemRequest("Milch", "1 l", null, null));
        at(NOW.plus(Duration.ofMinutes(1))).createItem("felix", new ItemRequest("2 Zwiebeln", null, null, null));
        at(NOW.plus(Duration.ofMinutes(2))).createItem("felix", new ItemRequest("Äpfel", null, null, null));
        at(NOW.plus(Duration.ofMinutes(3))).createItem("felix", new ItemRequest("Butter", null, null, null));
        Board board = service.board("felix");
        assertEquals(List.of("2 Zwiebeln", "Äpfel", "Milch", "Butter", "Klopapier"),
                board.items().stream().map(Item::name).toList());
        assertEquals(List.of("produce", "produce", "dairy", "dairy", "household"),
                board.items().stream().map(Item::category).toList());
        assertEquals("produce", board.categories().get(0).key());
        assertEquals("other", board.categories().get(board.categories().size() - 1).key());
        assertEquals("🥦", board.categories().get(0).emoji());
        assertEquals("carrot", board.categories().get(0).symbol());
        assertEquals("#34A853", board.categories().get(0).color());
    }

    @Test
    void handWahlWirdGelerntUndGiltAuchFuerGerichteUndRegeln() {
        Board board = service.createItem("felix", new ItemRequest("Kokosmilch", null, null, "dairy"));
        assertEquals("dairy", board.items().get(0).category());
        assertEquals("dairy", stored.get().learned().get("kokosmilch"));

        // Dasselbe Wort als Zutat: die gelernte Kategorie, nicht das Woerterbuch.
        board = service.createDish("felix", new DishRequest("Curry", List.of(
                new DishRequest.IngredientRequest("Kokosmilch", "1 Dose"))));
        board = service.addDish("felix", board.dishes().get(0).id());
        assertEquals("dairy", board.items().stream().filter(i -> i.dishId() != null).findFirst().get().category());

        // Und aus einer Regel, sogar in anderer Schreibweise.
        board = service.createRule("felix", new RecurringRequest("kokosmilch", null, 7, null));
        assertEquals("dairy", board.items().stream().filter(i -> i.ruleId() != null).findFirst().get().category());

        // Aendern ohne Kategorie: neu geraten - und das ist die gelernte.
        String id = board.items().get(0).id();
        assertEquals("dairy", service.updateItem("felix", id, new ItemRequest("Kokosmilch", "2", null, null))
                .items().get(0).category());
        // Unbekannter Schluessel: 400.
        assertThrows(ResponseStatusException.class,
                () -> service.createItem("felix", new ItemRequest("X", null, null, "unsinn")));
    }

    @Test
    void regelSetztSichSelbstAberNichtDoppelt() {
        Board board = service.createRule("felix", new RecurringRequest("Klopapier", "1 Pack", 14, null));
        assertEquals(1, board.recurring().size());
        String ruleId = board.recurring().get(0).id();
        // Ohne nextAt: heute - und damit sofort auf der Liste.
        assertEquals(1, board.items().size());
        Item klopapier = board.items().get(0);
        assertEquals(ruleId, klopapier.ruleId());
        assertEquals(ShoppingService.BY_RULE, klopapier.addedBy());
        assertEquals("1 Pack", klopapier.quantity());
        assertEquals(LocalDate.of(2026, 9, 17), board.recurring().get(0).nextAt());

        // Ein zweiter Lauf, sogar Wochen spaeter: solange der Eintrag offen ist, kein zweiter.
        ShoppingService later = at(NOW.plus(Duration.ofDays(30)));
        later.runRecurring();
        assertEquals(1, stored.get().items().size());

        // Abhaken nach drei Tagen: der Rhythmus zaehlt ab dem Kauf.
        ShoppingService threeDays = at(NOW.plus(Duration.ofDays(3)));
        Board checked = threeDays.check("freundin", klopapier.id());
        assertEquals(LocalDate.of(2026, 9, 20), checked.recurring().get(0).nextAt());

        // Am Faelligkeitstag kommt ein neuer Eintrag.
        ShoppingService due = at(Instant.parse("2026-09-20T06:00:00Z"));
        due.runRecurring();
        List<Item> open = stored.get().items().stream().filter(i -> i.checkedAt() == null).toList();
        assertEquals(1, open.size());
        assertEquals(ruleId, open.get(0).ruleId());
    }

    @Test
    void regelMitTerminWartetBisDahin() {
        Board board = service.createRule("felix",
                new RecurringRequest("Kaffee", null, 7, LocalDate.of(2026, 9, 10)));
        assertTrue(board.items().isEmpty());
        at(Instant.parse("2026-09-10T05:00:00Z")).runRecurring();
        assertEquals("Kaffee", stored.get().items().get(0).name());
        assertEquals(LocalDate.of(2026, 9, 17), stored.get().recurring().get(0).nextAt());
    }

    @Test
    void gerichtAlsGanzesAufDieListe() {
        Board board = service.createDish("felix", new DishRequest("Bolognese", List.of(
                new DishRequest.IngredientRequest("Hackfleisch", "500 g"),
                new DishRequest.IngredientRequest("Zwiebeln", "2"),
                new DishRequest.IngredientRequest("  ", null))));
        assertEquals(2, board.dishes().get(0).ingredients().size(), "leere Zeilen sind keine Zutaten");
        String dishId = board.dishes().get(0).id();

        board = service.addDish("felix", dishId);
        assertEquals(2, board.items().size());
        // Sortiert nach Rundgang: Zwiebeln (Gemuese) vor Hackfleisch (Fleisch).
        assertEquals(List.of("Zwiebeln", "Hackfleisch"), board.items().stream().map(Item::name).toList());
        Item hack = board.items().get(1);
        assertEquals("500 g", hack.quantity());
        assertEquals("Bolognese", hack.note());
        assertEquals(dishId, hack.dishId());
        assertEquals("meat", hack.category());

        // Noch einmal: Duplikate sind erlaubt.
        assertEquals(4, service.addDish("felix", dishId).items().size());
        // Gericht loeschen laesst die Eintraege stehen.
        assertEquals(4, service.deleteDish("felix", dishId).items().size());
    }

    @Test
    void abgehakteEntfernenLoeschtWirklich() {
        String a = service.createItem("felix", new ItemRequest("A", null, null, null)).items().get(0).id();
        service.createItem("felix", new ItemRequest("B", null, null, null));
        service.check("felix", a);
        Board board = service.clearChecked("felix");
        assertEquals(List.of("B"), board.items().stream().map(Item::name).toList());
        assertEquals(1, stored.get().items().size());
    }

    @Test
    void aufraeumerLoeschtNachNeunzigTagen() {
        String a = service.createItem("felix", new ItemRequest("Alt", null, null, null)).items().get(0).id();
        service.createItem("felix", new ItemRequest("Offen", null, null, null));
        service.check("felix", a);
        at(NOW.plus(Duration.ofDays(89))).cleanup();
        assertEquals(2, stored.get().items().size());
        at(NOW.plus(Duration.ofDays(91))).cleanup();
        assertEquals(List.of("Offen"), stored.get().items().stream().map(Item::name).toList());
    }

    @Test
    void pruefungen() {
        assertThrows(ResponseStatusException.class, () -> service.createItem("felix", new ItemRequest("  ", null, null, null)));
        assertThrows(ResponseStatusException.class, () -> service.createRule("felix", new RecurringRequest("X", null, 0, null)));
        assertThrows(ResponseStatusException.class, () -> service.createDish("felix", new DishRequest("", List.of())));
        assertThrows(ResponseStatusException.class, () -> service.check("felix", "gibt-es-nicht"));
        Board leer = service.createDish("felix", new DishRequest("Wasser", List.of()));
        assertThrows(ResponseStatusException.class, () -> service.addDish("felix", leer.dishes().get(0).id()));
    }

    @Test
    void aendernBehaeltUrheberUndHaken() {
        Board board = service.createItem("felix", new ItemRequest("Milch", "1", null, null));
        String id = board.items().get(0).id();
        service.check("freundin", id);
        board = service.updateItem("felix", id, new ItemRequest("Hafermilch", null, "vegan", null));
        Item item = board.items().get(0);
        assertEquals("Hafermilch", item.name());
        assertNull(item.quantity());
        assertEquals("vegan", item.note());
        assertEquals("felix", item.addedBy());
        assertNotNull(item.checkedAt());
        assertEquals("freundin", item.checkedBy());
    }
}
