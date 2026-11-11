package com.fherrmann.shopping.healthy;

import com.fherrmann.shopping.dto.CookResult;
import com.fherrmann.shopping.dto.CookResult.PersonResult;
import com.fherrmann.shopping.dto.DishRequest;
import com.fherrmann.shopping.repository.ShoppingRepository;
import com.fherrmann.shopping.security.TokenRegistry;
import com.fherrmann.shopping.service.Categorizer;
import com.fherrmann.shopping.service.RecipeSaved;
import com.fherrmann.shopping.service.ShoppingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CookServiceTest {

    @TempDir
    Path dir;

    private ShoppingService shopping;
    private FakeHealthy healthy;
    private CookService cook;

    /** Ein Kalorienzaehler, der tut, was der Test ihm sagt, und mitschreibt. */
    private static final class FakeHealthy implements Healthy {
        boolean configured = true;
        final List<List<String>> persons = new ArrayList<>();
        final List<HealthyDish> dishes = new ArrayList<>();
        final List<String> previousNames = new ArrayList<>();
        BiFunction<List<String>, HealthyDish, List<Outcome>> answer = (persons, dish) -> persons.stream()
                .map(person -> new Outcome(person, "created", null)).toList();

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public List<Outcome> writeDish(List<String> persons, HealthyDish dish, String previousName) {
            this.persons.add(persons);
            this.dishes.add(dish);
            this.previousNames.add(previousName);
            return answer.apply(persons, dish);
        }
    }

    @BeforeEach
    void setUp() {
        shopping = new ShoppingService(
                new ShoppingRepository(dir.resolve("shopping.json").toString(), JsonMapper.builder().findAndAddModules().build()),
                new Categorizer(), Clock.systemUTC(), 90);
        healthy = new FakeHealthy();
        cook = new CookService(shopping, healthy, new TokenRegistry("felix:token-felix-1,joana:token-joana-2"));
    }

    private String recipe(String name, DishRequest.NutritionRequest nutrition, Double grams) {
        shopping.createDish("felix", new DishRequest(name,
                List.of(new DishRequest.IngredientRequest("Penne", "250 g")), 2, 25, List.of("Kochen."), nutrition, grams));
        return shopping.board("felix").dishes().stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow().id();
    }

    private static final DishRequest.NutritionRequest NUTRITION = new DishRequest.NutritionRequest(640.0, 24.0, 78.0, 24.0);

    /** Das Rezept wird bei allen, die die Liste benutzen, zum Gericht - je 100 g, mit Vorsatz. */
    @Test
    void kochenLegtDasGerichtBeiAllenAn() {
        String id = recipe("Pasta mit Feta", NUTRITION, 420.0);

        CookResult result = cook.cook("joana", id);

        assertThat(healthy.persons).containsExactly(List.of("felix", "joana"));
        // 640 kcal je 420 g sind 152,38 je 100 g; die Portion bleibt 420 g.
        assertThat(healthy.dishes).containsExactly(
                new HealthyDish("Rezept: Pasta mit Feta", 152.38, 5.71, 18.57, 5.71, 420.0));
        assertThat(result.dishId()).isEqualTo(id);
        assertThat(result.reason()).isNull();
        assertThat(result.healthy()).containsExactly(
                new PersonResult("felix", "created", null), new PersonResult("joana", "created", null));
    }

    /** Wer kein Healthy hat, fehlt in der Antwort - das ist kein Fehler. Abgelehnt und unveraendert bleiben stehen. */
    @Test
    void werKeinHealthyHatStehtNichtInDerAntwort() {
        String id = recipe("Pasta mit Feta", NUTRITION, 420.0);
        healthy.answer = (persons, dish) -> List.of(
                new Healthy.Outcome("felix", "unchanged", null),
                new Healthy.Outcome("joana", Healthy.Outcome.NOT_LINKED, null),
                new Healthy.Outcome("fremd", "created", null));
        assertThat(cook.cook("felix", id).healthy()).containsExactly(new PersonResult("felix", "unchanged", null));

        healthy.answer = (persons, dish) -> List.of(
                new Healthy.Outcome("felix", "updated", null),
                new Healthy.Outcome("joana", "rejected", "name is too long"));
        assertThat(cook.cook("felix", id).healthy()).containsExactly(
                new PersonResult("felix", "updated", null), new PersonResult("joana", "rejected", "name is too long"));
    }

    /** Faellt der Kalorienzaehler aus, wird trotzdem gekocht - die Antwort sagt es je Person. */
    @Test
    void einAusfallTrifftAlleUndKommtAlsErgebnisNichtAlsFehler() {
        String id = recipe("Pasta mit Feta", NUTRITION, 420.0);
        healthy.answer = (persons, dish) -> {
            throw new HealthyException("Kalorienzähler nicht erreichbar.");
        };
        CookResult result = cook.cook("felix", id);
        assertThat(result.reason()).isNull();
        assertThat(result.healthy()).containsExactly(
                new PersonResult("felix", "failed", "Kalorienzähler nicht erreichbar."),
                new PersonResult("joana", "failed", "Kalorienzähler nicht erreichbar."));

        // Eine Antwort, in der jemand fehlt: fuer die Person ist es ein Ausfall.
        healthy.answer = (persons, dish) -> List.of(new Healthy.Outcome("felix", "created", null));
        assertThat(cook.cook("felix", id).healthy()).containsExactly(
                new PersonResult("felix", "created", null),
                new PersonResult("joana", "failed", "Keine Antwort vom Kalorienzähler."));
    }

    /** Einkaufsliste und Uebergabe wie im Dienst verdrahtet - nur ohne Hintergrund-Faden. */
    private ShoppingService wired() {
        CookService[] handover = new CookService[1];
        ShoppingService live = new ShoppingService(
                new ShoppingRepository(dir.resolve("live.json").toString(), JsonMapper.builder().findAndAddModules().build()),
                new Categorizer(), Clock.systemUTC(), 90, event -> {
                    if (event instanceof RecipeSaved saved) {
                        handover[0].onRecipeSaved(saved);
                    }
                });
        handover[0] = new CookService(live, healthy,
                new TokenRegistry("felix:token-felix-1,joana:token-joana-2"), Runnable::run);
        return live;
    }

    private static DishRequest request(String name, DishRequest.NutritionRequest nutrition, Double grams) {
        return new DishRequest(name, List.of(new DishRequest.IngredientRequest("Penne", "250 g")),
                2, 25, List.of("Kochen."), nutrition, grams);
    }

    /** Gespeichert heisst eintragbar (Felix, 11.11.): ohne "Kochen" steht das Gericht bei beiden. */
    @Test
    void einGespeichertesRezeptStehtGleichInHealthy() {
        ShoppingService live = wired();

        live.createDish("joana", request("Pasta mit Feta", NUTRITION, 420.0));

        assertThat(healthy.persons).containsExactly(List.of("felix", "joana"));
        assertThat(healthy.dishes).containsExactly(
                new HealthyDish("Rezept: Pasta mit Feta", 152.38, 5.71, 18.57, 5.71, 420.0));
        assertThat(healthy.previousNames).containsOnlyNulls();

        // Ohne Naehrwerte gibt es dort nichts einzutragen.
        live.createDish("felix", request("Suppe", null, null));
        assertThat(healthy.dishes).hasSize(1);
    }

    /** Aendern zieht nach; Umbenennen schickt den alten Namen mit, damit dort kein zweites entsteht. */
    @Test
    void einGeaendertesRezeptZiehtNachUndUmbenennenNimmtDenAltenNamenMit() {
        ShoppingService live = wired();
        live.createDish("felix", request("Pasta mit Feta", NUTRITION, 420.0));
        String id = live.board("felix").dishes().getFirst().id();

        live.updateDish("felix", id, request("Pasta mit Feta",
                new DishRequest.NutritionRequest(700.0, 30.0, 80.0, 25.0), 420.0));
        live.updateDish("felix", id, new DishRequest("Feta-Pasta",
                List.of(new DishRequest.IngredientRequest("Penne", "250 g")), null, null, null, null, null));

        assertThat(healthy.dishes).extracting(HealthyDish::name)
                .containsExactly("Rezept: Pasta mit Feta", "Rezept: Pasta mit Feta", "Rezept: Feta-Pasta");
        assertThat(healthy.dishes.get(1).kcal()).isEqualTo(166.67);
        assertThat(healthy.dishes.get(2).kcal()).isEqualTo(166.67);
        assertThat(healthy.previousNames).containsExactly(null, null, "Rezept: Pasta mit Feta");
    }

    /** Faellt der Kalorienzaehler aus, ist das Rezept trotzdem gespeichert. */
    @Test
    void einAusfallHaeltDasSpeichernNichtAuf() {
        ShoppingService live = wired();
        healthy.answer = (persons, dish) -> {
            throw new HealthyException("Kalorienzähler nicht erreichbar.");
        };

        live.createDish("felix", request("Pasta mit Feta", NUTRITION, 420.0));

        assertThat(live.board("felix").dishes()).extracting(d -> d.name()).containsExactly("Pasta mit Feta");
        assertThat(healthy.dishes).hasSize(1);
    }

    /** Beim Start einmal alle mit Naehrwerten - was beim Speichern nicht ankam, kommt so nach. */
    @Test
    void derAbgleichBeimStartHoltAlleRezepteMitNaehrwertenNach() {
        recipe("Pasta mit Feta", NUTRITION, 420.0);
        recipe("Suppe", null, null);
        recipe("Curry", new DishRequest.NutritionRequest(500.0, 20.0, 60.0, 18.0), 400.0);
        cook = new CookService(shopping, healthy, new TokenRegistry("felix:token-felix-1,joana:token-joana-2"),
                Runnable::run);

        cook.publishAll();
        assertThat(healthy.dishes).extracting(HealthyDish::name)
                .containsExactlyInAnyOrder("Rezept: Pasta mit Feta", "Rezept: Curry");

        // Nicht erreichbar: nach dem ersten Versuch ist Schluss, statt je Rezept zu warten.
        healthy.dishes.clear();
        healthy.answer = (persons, dish) -> {
            throw new HealthyException("Kalorienzähler nicht erreichbar.");
        };
        cook.publishAll();
        assertThat(healthy.dishes).hasSize(1);

        // Ohne Token wird nicht gefragt.
        healthy.dishes.clear();
        healthy.configured = false;
        cook.publishAll();
        assertThat(healthy.dishes).isEmpty();
    }

    @Test
    void ohneNaehrwerteOderOhneTokenWirdNichtGefragt() {
        String plain = recipe("Suppe", null, null);
        String weightOnly = recipe("Brot", null, 300.0);
        assertThat(cook.cook("felix", plain)).isEqualTo(new CookResult(plain, CookResult.NO_NUTRITION, List.of()));
        assertThat(cook.cook("felix", weightOnly).reason()).isEqualTo("no-nutrition");

        healthy.configured = false;
        String full = recipe("Pasta mit Feta", NUTRITION, 420.0);
        assertThat(cook.cook("felix", full)).isEqualTo(new CookResult(full, CookResult.NOT_CONFIGURED, List.of()));
        // Ohne Naehrwerte ist das der Grund - nicht der fehlende Token.
        assertThat(cook.cook("felix", plain).reason()).isEqualTo("no-nutrition");
        assertThat(healthy.dishes).isEmpty();
    }

    @Test
    void einUnbekanntesRezeptIstEin404() {
        assertThatThrownBy(() -> cook.cook("felix", "gibt-es-nicht"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }

    /** Der Kalorienzaehler nimmt 120 Zeichen - laengere Namen werden gekuerzt, nicht abgelehnt. */
    @Test
    void derNameBekommtDenVorsatzUndPasstInDenKalorienzaehler() {
        assertThat(CookService.foodName("  Pasta   mit Feta ")).isEqualTo("Rezept: Pasta mit Feta");
        String longName = CookService.foodName("x".repeat(200));
        assertThat(longName).hasSize(CookService.MAX_FOOD_NAME).startsWith("Rezept: xxx").endsWith("…");
        assertThat(CookService.foodName("y".repeat(112))).hasSize(120).doesNotEndWith("…");
    }

    @Test
    void rechnetJePortionAufJeHundertGramm() {
        assertThat(CookService.per100(640, 420)).isEqualTo(152.38);
        assertThat(CookService.per100(24, 420)).isEqualTo(5.71);
        assertThat(CookService.per100(500, 500)).isEqualTo(100.0);
        assertThat(CookService.per100(0, 300)).isEqualTo(0.0);
        assertThat(CookService.per100(98, 480)).isEqualTo(20.42);
    }
}
