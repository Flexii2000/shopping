package com.fherrmann.shopping.service;

import com.fherrmann.shopping.dto.Board;
import com.fherrmann.shopping.dto.ClearCheckedRequest;
import com.fherrmann.shopping.dto.DishAddRequest;
import com.fherrmann.shopping.dto.DishRequest;
import com.fherrmann.shopping.dto.ItemRequest;
import com.fherrmann.shopping.dto.RecurringRequest;
import com.fherrmann.shopping.dto.ScaledDish;
import com.fherrmann.shopping.model.Dish;
import com.fherrmann.shopping.model.Ingredient;
import com.fherrmann.shopping.model.Item;
import com.fherrmann.shopping.model.Nutrition;
import com.fherrmann.shopping.model.ShoppingData;
import com.fherrmann.shopping.repository.ShoppingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void dieMengeImNamenWirdHerausgeloestUndDerRestZugeordnet() {
        Board board = service.createItem("felix", new ItemRequest("gemischtes Hack 200g", null, null, null));
        Item hack = board.items().get(0);
        assertEquals("gemischtes Hack", hack.name());
        assertEquals("200 g", hack.quantity());
        assertEquals("meat", hack.category());

        board = service.createItem("felix", new ItemRequest("Mehl Type 405", null, null, null));
        Item mehl = board.items().stream().filter(i -> i.name().startsWith("Mehl")).findFirst().orElseThrow();
        assertEquals("Mehl Type 405", mehl.name());
        assertNull(mehl.quantity());

        // Beides angegeben: der Name bleibt, die Menge wird nur vereinheitlicht.
        board = service.createItem("felix", new ItemRequest("Hack 200g", "500g", null, null));
        Item beides = board.items().stream().filter(i -> i.name().equals("Hack 200g")).findFirst().orElseThrow();
        assertEquals("500 g", beides.quantity());
    }

    @Test
    void dosenUndGlaeserSindKonservenAusserJemandHatEsAndersGelernt() {
        Board board = service.createItem("felix", new ItemRequest("Tomaten 3 Dosen", null, null, null));
        Item tomaten = board.items().get(0);
        assertEquals("Tomaten", tomaten.name());
        assertEquals("3 Dosen", tomaten.quantity());
        assertEquals("canned", tomaten.category());

        board = service.createItem("felix", new ItemRequest("Gurken", "1 Glas", null, null));
        assertEquals("canned", board.items().stream().filter(i -> i.name().equals("Gurken")).findFirst().orElseThrow().category());

        // Von Hand gesetzt schlaegt die Verpackung - und derselbe Name wird
        // ein Eintrag mit zusammengefasster Menge.
        service.updateItem("felix", tomaten.id(), new ItemRequest("Tomaten", "3 Dosen", null, "produce"));
        board = service.createItem("felix", new ItemRequest("Tomaten 2 Dosen", null, null, null));
        Item zusammen = board.items().stream().filter(i -> i.name().equals("Tomaten")).findFirst().orElseThrow();
        assertEquals("5 Dosen", zusammen.quantity());
        assertEquals("produce", zusammen.category());
    }

    @Test
    void derselbeNameWirdEinEintragEgalWieGeschrieben() {
        service.createItem("felix", new ItemRequest("Rinderhack 500g", null, null, null));
        Board board = service.createItem("joana", new ItemRequest("rinderhack 500 g", null, null, null));
        assertEquals(1, board.items().size());
        Item hack = board.items().get(0);
        assertEquals("Rinderhack", hack.name());
        assertEquals("1000 g", hack.quantity());
        assertEquals("felix", hack.addedBy());

        // Abgehakt zaehlt nicht mehr als "steht schon drauf": das ist ein neuer Kauf.
        service.check("felix", hack.id());
        board = service.createItem("felix", new ItemRequest("RINDERHACK", null, null, null));
        assertEquals(2, board.items().size());

        // Auch ein Gericht legt keine zweite Zeile an.
        service.createItem("felix", new ItemRequest("Zwiebeln", "1 Stk", null, null));
        Board dishes = service.createDish("felix", new DishRequest("Suppe", List.of(new DishRequest.IngredientRequest("zwiebeln", "2 Stk"))));
        board = service.addDish("felix", dishes.dishes().get(0).id());
        Item zwiebeln = board.items().stream().filter(i -> i.name().equals("Zwiebeln")).findFirst().orElseThrow();
        assertEquals("3 Stk", zwiebeln.quantity());
        assertEquals("Suppe", zwiebeln.note());
        assertEquals(1, board.items().stream().filter(i -> i.name().equalsIgnoreCase("zwiebeln")).count());
    }

    @Test
    void abgehaktBleibtBisMitternachtSichtbarUndDannNurInDerDatei() {
        Board board = service.createItem("felix", new ItemRequest("Milch", "2", null, null));
        assertEquals("felix", board.me());
        Item milch = board.items().get(0);
        assertEquals("felix", milch.addedBy());
        assertNull(milch.checkedAt());

        board = service.check("joana", milch.id());
        assertEquals("joana", board.items().get(0).checkedBy());
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
        Board again = at(NOW.plus(Duration.ofHours(1))).check("joana", id);
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
        assertEquals(List.of("Zwiebeln", "Äpfel", "Milch", "Butter", "Klopapier"),
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
        assertEquals("1 Pck", klopapier.quantity());
        assertEquals(LocalDate.of(2026, 9, 17), board.recurring().get(0).nextAt());

        // Ein zweiter Lauf, sogar Wochen spaeter: solange der Eintrag offen ist, kein zweiter.
        ShoppingService later = at(NOW.plus(Duration.ofDays(30)));
        later.runRecurring();
        assertEquals(1, stored.get().items().size());

        // Abhaken nach drei Tagen: der Rhythmus zaehlt ab dem Kauf.
        ShoppingService threeDays = at(NOW.plus(Duration.ofDays(3)));
        Board checked = threeDays.check("joana", klopapier.id());
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

        // Noch einmal: keine zweite Zeile, die Mengen kommen zusammen.
        board = service.addDish("felix", dishId);
        assertEquals(2, board.items().size());
        assertEquals("1000 g", board.items().get(1).quantity());
        assertEquals("4 Stk", board.items().get(0).quantity());
        // Gericht loeschen laesst die Eintraege stehen.
        assertEquals(2, service.deleteDish("felix", dishId).items().size());
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
        service.check("joana", id);
        board = service.updateItem("felix", id, new ItemRequest("Hafermilch", null, "vegan", null));
        Item item = board.items().get(0);
        assertEquals("Hafermilch", item.name());
        assertNull(item.quantity());
        assertEquals("vegan", item.note());
        assertEquals("felix", item.addedBy());
        assertNotNull(item.checkedAt());
        assertEquals("joana", item.checkedBy());
    }

    // MARK: - Kennungen der App (offline angelegt, spaeter nachgesendet)

    private static final String A = "1a2b3c4d-0000-4000-8000-00000000000a";
    private static final String B = "1a2b3c4d-0000-4000-8000-00000000000b";
    private static final String C = "1a2b3c4d-0000-4000-8000-00000000000c";

    private Item item(Board board, String name) {
        return board.items().stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow();
    }

    /** Die App vergibt die Kennung; nachgesendet zaehlt dasselbe Anlegen nicht noch einmal. */
    @Test
    void dieKennungDerAppGiltUndNachgesendetDoppeltNichts() {
        Board board = service.createItem("felix", new ItemRequest("Milch", "2 l", null, null, A));
        assertEquals(A, item(board, "Milch").id());
        board = service.createItem("felix", new ItemRequest("Milch", "2 l", null, null, A));
        assertEquals(1, board.items().size());
        assertEquals("2 l", item(board, "Milch").quantity(), "nicht 4 l");
    }

    /** Geht der neue Eintrag in einem offenen gleichen Namens auf, trifft die Kennung der App diesen. */
    @Test
    void eineAufgegangeneKennungTrifftDenZusammengefasstenEintrag() {
        String joanas = service.createItem("joana", new ItemRequest("Milch", "1 l", null, null)).items().get(0).id();
        Board board = service.createItem("felix", new ItemRequest("Milch", "2 l", null, null, A));
        assertEquals(1, board.items().size());
        assertEquals("3 l", item(board, "Milch").quantity());
        // Nachgesendet: nicht noch einmal 2 l dazu.
        board = service.createItem("felix", new ItemRequest("Milch", "2 l", null, null, A));
        assertEquals("3 l", item(board, "Milch").quantity());

        board = service.check("felix", A);
        assertEquals(joanas, item(board, "Milch").id());
        assertNotNull(item(board, "Milch").checkedAt());
        board = service.uncheck("felix", A);
        assertNull(item(board, "Milch").checkedAt());
        board = service.updateItem("felix", A, new ItemRequest("Milch", "4 l", null, null));
        assertEquals("4 l", item(board, "Milch").quantity());
        board = service.deleteItem("felix", A);
        assertTrue(board.items().isEmpty());
    }

    /** Ein spaet nachgesendetes Anlegen holt keinen Eintrag zurueck, den inzwischen jemand geloescht hat. */
    @Test
    void geloeschtBleibtGeloescht() {
        service.createItem("felix", new ItemRequest("Brot", null, null, null, A));
        service.deleteItem("joana", A);
        Board board = service.createItem("felix", new ItemRequest("Brot", null, null, null, A));
        assertTrue(board.items().isEmpty());
    }

    @Test
    void nurKleingeschriebeneUuids() {
        assertThrows(ResponseStatusException.class,
                () -> service.createItem("felix", new ItemRequest("Brot", null, null, null, "brot-1")));
        assertThrows(ResponseStatusException.class,
                () -> service.createItem("felix", new ItemRequest("Brot", null, null, null, A.toUpperCase())));
    }

    /** "Abgehakte entfernen" mit Liste: was die andere Person danach abgehakt hat, bleibt. */
    @Test
    void abgehakteEntfernenNurFuerDieGenannten() {
        service.createItem("felix", new ItemRequest("Brot", null, null, null, A));
        service.createItem("felix", new ItemRequest("Eier", null, null, null, B));
        service.createItem("felix", new ItemRequest("Salz", null, null, null, C));
        service.check("felix", A);
        service.check("joana", B);
        Board board = service.clearChecked("felix", new ClearCheckedRequest(List.of(A, C)));
        assertEquals(List.of("Salz", "Eier"), board.items().stream().map(Item::name).toList(),
                "Brot weg; Eier (nicht genannt) und Salz (nicht abgehakt) bleiben");
        board = service.clearChecked("joana", null);
        assertEquals(List.of("Salz"), board.items().stream().map(Item::name).toList(), "ohne Liste alle abgehakten");
    }

    /** Ein Gericht mit den Kennungen der App: je Zutat die eigene, nachgesendet aendert sich nichts. */
    @Test
    void einGerichtMitDenKennungenDerApp() {
        service.createItem("joana", new ItemRequest("Zwiebeln", "1 Stk", null, null));
        String dish = service.createDish("felix", new DishRequest("Suppe", List.of(
                new DishRequest.IngredientRequest("Zwiebeln", "2 Stk"),
                new DishRequest.IngredientRequest("Lauch", "1 Stange")))).dishes().get(0).id();
        DishAddRequest request = new DishAddRequest(List.of(
                new DishAddRequest.Entry("zwiebeln", A), new DishAddRequest.Entry("Lauch", B)));
        Board board = service.addDish("felix", dish, request);
        assertEquals(B, item(board, "Lauch").id());
        assertEquals("3 Stk", item(board, "Zwiebeln").quantity());
        board = service.addDish("felix", dish, request);
        assertEquals("3 Stk", item(board, "Zwiebeln").quantity(), "nachgesendet: nichts doppelt");
        assertEquals(2, board.items().size());
        board = service.check("felix", A);
        assertNotNull(item(board, "Zwiebeln").checkedAt(), "die Kennung der Zwiebeln trifft den zusammengefassten Eintrag");
    }

    /** Der Aufraeumer nimmt auch alte Kennungen mit - nach derselben Frist. */
    @Test
    void alteKennungenFliegenRaus() {
        service.createItem("felix", new ItemRequest("Brot", null, null, null, A));
        assertEquals(1, stored.get().clientIds().size());
        at(NOW.plus(Duration.ofDays(91))).cleanup();
        assertTrue(stored.get().clientIds().isEmpty());
        assertEquals(1, stored.get().items().size(), "der offene Eintrag bleibt");
    }

    // MARK: - Rezepte: Portionen, Schritte, Naehrwerte

    private static DishRequest.IngredientRequest zutat(String name, String quantity, Boolean pantry) {
        return new DishRequest.IngredientRequest(name, quantity, pantry);
    }

    /** Pasta mit Feta fuer zwei: zwei Einkaeufe, ein Vorrat, eine Zutat ohne Menge. */
    private static DishRequest pasta() {
        return new DishRequest("Pasta mit Feta",
                List.of(zutat("Penne", "250 g", false), zutat("Feta", "1 Pck", null),
                        zutat("Olivenöl", "2 EL", true), zutat("Salz", null, true)),
                2, 25, List.of("Wasser aufsetzen.", "  ", "Penne kochen."),
                new DishRequest.NutritionRequest(640.0, 24.0, 78.0, 24.0), 420.0);
    }

    private Dish onlyDish() {
        assertEquals(1, stored.get().dishes().size());
        return stored.get().dishes().get(0);
    }

    private static void assertStatus(HttpStatus expected, Runnable call) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, call::run);
        assertEquals(expected, e.getStatusCode(), e.getReason());
    }

    @Test
    void einRezeptMitPortionenSchrittenUndNaehrwerten() {
        Board board = service.createDish("joana", pasta());
        Dish dish = board.dishes().get(0);
        assertEquals("Pasta mit Feta", dish.name());
        assertEquals(2, dish.portions());
        assertEquals(25, dish.minutes());
        assertEquals(List.of("Wasser aufsetzen.", "Penne kochen."), dish.steps(), "leere Zeilen sind keine Schritte");
        assertEquals(new Nutrition(640.0, 24.0, 78.0, 24.0), dish.nutrition());
        assertEquals(420.0, dish.gramsPerPortion());
        assertEquals("joana", dish.createdBy());
        assertNull(dish.source(), "von Hand angelegt");
        assertEquals(List.of(new Ingredient("Penne", "250 g", false), new Ingredient("Feta", "1 Pck", false),
                new Ingredient("Olivenöl", "2 EL", true), new Ingredient("Salz", null, true)), dish.ingredients());

        // Aus dem Rezept-Chat gespeichert traegt es seine Herkunft.
        Dish fromChat = service.createRecipe("felix", new DishRequest("Curry", List.of(zutat("Reis", "200 g", null))), "chat");
        assertEquals("chat", fromChat.source());
        assertEquals("felix", fromChat.createdBy());
        assertEquals(fromChat, service.dish(fromChat.id()));
    }

    /** Aeltere Apps schicken beim Speichern nur Name und Zutaten - der Rest des Rezepts muss das ueberleben. */
    @Test
    void einAelteresSpeichernLoeschtNichts() {
        String id = service.createDish("felix", pasta()).dishes().get(0).id();

        service.updateDish("joana", id, new DishRequest("Pasta mit Feta und Tomaten", List.of(
                new DishRequest.IngredientRequest("Penne", "300 g"),
                new DishRequest.IngredientRequest("olivenöl", "3 EL"),
                new DishRequest.IngredientRequest("Tomaten", "4"))));

        Dish dish = onlyDish();
        assertEquals("Pasta mit Feta und Tomaten", dish.name());
        assertEquals(2, dish.portions());
        assertEquals(25, dish.minutes());
        assertEquals(List.of("Wasser aufsetzen.", "Penne kochen."), dish.steps());
        assertEquals(new Nutrition(640.0, 24.0, 78.0, 24.0), dish.nutrition());
        assertEquals(420.0, dish.gramsPerPortion());
        assertEquals("felix", dish.createdBy(), "wer es angelegt hat, bleibt");
        // Die Zutaten ersetzen die alten wie immer; das Vorrats-Kennzeichen erbt die gleichnamige.
        assertEquals(List.of(new Ingredient("Penne", "300 g", false), new Ingredient("olivenöl", "3 EL", true),
                new Ingredient("Tomaten", "4 Stk", false)), dish.ingredients());

        // Ausdruecklich gesetzt gilt, was dasteht.
        service.updateDish("joana", id, new DishRequest("Pasta mit Feta und Tomaten",
                List.of(zutat("Olivenöl", "3 EL", false)), null, null, null, null, null));
        assertEquals(List.of(new Ingredient("Olivenöl", "3 EL", false)), onlyDish().ingredients());
    }

    @Test
    void derLeereWertLoescht() {
        String id = service.createDish("felix", pasta()).dishes().get(0).id();
        List<DishRequest.IngredientRequest> zutaten = List.of(zutat("Penne", "250 g", null));

        // Das Gewicht allein loeschen geht nicht - die Naehrwerte haengen daran.
        assertStatus(HttpStatus.BAD_REQUEST,
                () -> service.updateDish("felix", id, new DishRequest("Pasta mit Feta", zutaten, null, null, null, null, 0.0)));

        service.updateDish("felix", id, new DishRequest("Pasta mit Feta", zutaten, 0, 0, List.of(),
                new DishRequest.NutritionRequest(null, null, null, null), 0.0));
        Dish dish = onlyDish();
        assertNull(dish.portions());
        assertNull(dish.minutes());
        assertEquals(List.of(), dish.steps());
        assertNull(dish.nutrition());
        assertNull(dish.gramsPerPortion());

        // Und wieder setzen.
        service.updateDish("felix", id, new DishRequest("Pasta mit Feta", zutaten, 4, null, List.of("Kochen."), null, null));
        assertEquals(4, onlyDish().portions());
        assertEquals(List.of("Kochen."), onlyDish().steps());
    }

    /** Der Kalorienzaehler ordnet das gekochte Rezept ueber den Namen zu - also ein Name, ein Rezept. */
    @Test
    void rezeptnamenSindEindeutig() {
        String pasta = service.createDish("felix", pasta()).dishes().get(0).id();
        assertStatus(HttpStatus.CONFLICT, () -> service.createDish("joana", new DishRequest("  pasta  MIT feta ", List.of())));
        String curry = service.createDish("felix", new DishRequest("Curry", List.of())).dishes().stream()
                .filter(d -> d.name().equals("Curry")).findFirst().orElseThrow().id();
        assertStatus(HttpStatus.CONFLICT, () -> service.updateDish("felix", curry, new DishRequest("Pasta mit Feta", List.of())));
        // Die eigene Schreibweise aendern ist kein Umbenennen auf einen vergebenen Namen.
        service.updateDish("felix", pasta, new DishRequest("PASTA mit Feta", List.of()));
        assertEquals(2, stored.get().dishes().size());

        // Zwei gleichnamige von frueher lassen sich weiter bearbeiten.
        Instant then = NOW.minus(Duration.ofDays(30));
        stored.set(new ShoppingData(List.of(), List.of(
                new Dish("a", "Suppe", List.of(), then), new Dish("b", "Suppe", List.of(), then)), List.of(), Map.of()));
        service.updateDish("felix", "a", new DishRequest("Suppe", List.of(new DishRequest.IngredientRequest("Lauch", "1 Stange"))));
        assertEquals(1, stored.get().dishes().get(0).ingredients().size());
    }

    @Test
    void naehrwerteBrauchenDasGewichtUndMuessenInDenKalorienzaehlerPassen() {
        List<DishRequest.IngredientRequest> zutaten = List.of(zutat("Penne", "250 g", null));
        DishRequest.NutritionRequest ok = new DishRequest.NutritionRequest(640.0, 24.0, 78.0, 24.0);
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createDish("felix", new DishRequest("A", zutaten, 2, null, null, ok, null)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createDish("felix", new DishRequest("A", zutaten, 2, null, null,
                new DishRequest.NutritionRequest(640.0, null, 78.0, 24.0), 420.0)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createDish("felix", new DishRequest("A", zutaten, 2, null, null,
                new DishRequest.NutritionRequest(640.0, -1.0, 78.0, 24.0), 420.0)));
        // 640 kcal in 50 g waeren 1280 kcal je 100 g - das nimmt der Kalorienzaehler nicht.
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createDish("felix", new DishRequest("A", zutaten, 2, null, null, ok, 50.0)));
        assertTrue(stored.get().dishes().isEmpty());
        // Das Gewicht allein ist in Ordnung.
        service.createDish("felix", new DishRequest("A", zutaten, 2, null, null, null, 420.0));
        assertEquals(420.0, onlyDish().gramsPerPortion());
        assertTrue(ShoppingService.fitsFood(new Nutrition(1000.0, 100.0, 100.0, 100.0), 100));
        assertFalse(ShoppingService.fitsFood(new Nutrition(640.0, 24.0, 78.0, 51.0), 50));
    }

    @Test
    void dieGrenzenEinesRezepts() {
        List<DishRequest.IngredientRequest> zutaten = List.of(zutat("Penne", "250 g", null));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createDish("felix", new DishRequest("A", zutaten, 100, null, null, null, null)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createDish("felix", new DishRequest("A", zutaten, -1, null, null, null, null)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createDish("felix", new DishRequest("A", zutaten, null, 1441, null, null, null)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createDish("felix", new DishRequest("A", zutaten, null, null, null, null, 5001.0)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createDish("felix", new DishRequest("A", zutaten, null, null, null, null, 0.5)));
        List<String> zuViele = new ArrayList<>();
        for (int i = 0; i <= ShoppingService.MAX_STEPS; i++) {
            zuViele.add("Schritt " + i);
        }
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createDish("felix", new DishRequest("A", zutaten, null, null, zuViele, null, null)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createDish("felix", new DishRequest("A", zutaten, null, null,
                List.of("x".repeat(ShoppingService.MAX_STEP + 1)), null, null)));
        List<DishRequest.IngredientRequest> viele = new ArrayList<>();
        for (int i = 0; i <= ShoppingService.MAX_INGREDIENTS; i++) {
            viele.add(zutat("Zutat " + i, null, null));
        }
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createDish("felix", new DishRequest("A", viele)));
        assertTrue(stored.get().dishes().isEmpty());
        // Genau an der Grenze geht es.
        service.createDish("felix", new DishRequest("A", viele.subList(0, ShoppingService.MAX_INGREDIENTS), 99, 1440,
                zuViele.subList(0, ShoppingService.MAX_STEPS), null, 5000.0));
        assertEquals(99, onlyDish().portions());
    }

    /** Das Rezept fuer vier statt zwei: doppelte Mengen, der Vorrat bleibt zu Hause. */
    @Test
    void aufDieListeFuerEineAnderePortionenzahl() {
        String id = service.createDish("felix", pasta()).dishes().get(0).id();
        service.createItem("joana", new ItemRequest("penne", "100 g", null, null));

        Board board = service.addDish("felix", id, new DishAddRequest(
                List.of(new DishAddRequest.Entry("Feta", A), new DishAddRequest.Entry("penne", B)),
                4, List.of("olivenöl", "  SALZ ")));
        assertEquals(2, board.items().size());
        assertEquals("600 g", item(board, "penne").quantity(), "500 g zu den 100 g, die schon draufstanden");
        assertEquals("Pasta mit Feta", item(board, "penne").note());
        assertEquals("2 Pck", item(board, "Feta").quantity());
        assertEquals(A, item(board, "Feta").id());

        // Nachgesendet: nichts doppelt.
        board = service.addDish("felix", id, new DishAddRequest(
                List.of(new DishAddRequest.Entry("Feta", A), new DishAddRequest.Entry("penne", B)),
                4, List.of("olivenöl", "salz")));
        assertEquals("600 g", item(board, "penne").quantity());
        // Die Kennung der Penne trifft den zusammengefassten Eintrag.
        assertNotNull(item(service.check("felix", B), "penne").checkedAt());

        // Fuer eine Portion: halbe Mengen, und was keine Menge hat, kommt ohne.
        board = service.addDish("joana", id, new DishAddRequest(null, 1, List.of("Feta", "Penne")));
        assertEquals("1 EL", item(board, "Olivenöl").quantity());
        assertNull(item(board, "Salz").quantity());
    }

    @Test
    void ohnePortionenBleibenDieMengenWieGespeichert() {
        String id = service.createDish("felix", pasta()).dishes().get(0).id();
        Board board = service.addDish("felix", id);
        assertEquals(4, board.items().size(), "auch der Vorrat - ohne Rumpf alles wie vor den Rezepten");
        assertEquals("250 g", item(board, "Penne").quantity());
        board = service.addDish("felix", id, new DishAddRequest(null, null, List.of("Penne")));
        assertEquals("250 g", item(board, "Penne").quantity(), "abgewaehlt");
        assertEquals("2 Pck", item(board, "Feta").quantity());
    }

    @Test
    void umrechnenBrauchtDiePortionenDesRezepts() {
        String ohne = service.createDish("felix", new DishRequest("Suppe", List.of(
                new DishRequest.IngredientRequest("Lauch", "1 Stange")))).dishes().get(0).id();
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.addDish("felix", ohne, new DishAddRequest(null, 4, null)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.scaled(ohne, 4));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.scaled(ohne, null));
        assertTrue(stored.get().items().isEmpty());

        String mit = service.createDish("felix", pasta()).dishes().stream()
                .filter(d -> d.name().startsWith("Pasta")).findFirst().orElseThrow().id();
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.addDish("felix", mit, new DishAddRequest(null, 0, null)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.addDish("felix", mit, new DishAddRequest(null, 100, null)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.scaled(mit, 0));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.scaled("gibt-es-nicht", 2));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.dish("gibt-es-nicht"));
    }

    @Test
    void dieZutatenFuerEineAnderePortionenzahl() {
        String id = service.createDish("felix", new DishRequest("Pasta",
                List.of(zutat("Penne", "250 g", false), zutat("Olivenöl", "2 EL", true),
                        zutat("Basilikum", "eine Handvoll", false), zutat("Salz", null, true)),
                2, null, null, null, null)).dishes().get(0).id();

        ScaledDish scaled = service.scaled(id, 3);
        assertEquals(id, scaled.dishId());
        assertEquals(3, scaled.portions());
        assertEquals(2, scaled.basePortions());
        assertEquals(List.of(
                new ScaledDish.ScaledIngredient("Penne", "375 g", false, true),
                new ScaledDish.ScaledIngredient("Olivenöl", "3 EL", true, true),
                new ScaledDish.ScaledIngredient("Basilikum", "eine Handvoll", false, false),
                new ScaledDish.ScaledIngredient("Salz", null, true, true)), scaled.ingredients());

        // Ohne Angabe die Portionen des Rezepts - dann ist auch die Handvoll richtig.
        ScaledDish base = service.scaled(id, null);
        assertEquals(2, base.portions());
        assertEquals(new ScaledDish.ScaledIngredient("Basilikum", "eine Handvoll", false, true), base.ingredients().get(2));
        assertTrue(stored.get().items().isEmpty(), "rechnen aendert nichts");
    }

    @Test
    void allesAbgewaehltLegtNichtsAn() {
        String id = service.createDish("felix", pasta()).dishes().get(0).id();
        Board board = service.addDish("felix", id, new DishAddRequest(null, 2, List.of("Penne", "Feta", "Olivenöl", "Salz")));
        assertTrue(board.items().isEmpty());
    }
}
