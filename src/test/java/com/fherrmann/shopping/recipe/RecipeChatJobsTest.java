package com.fherrmann.shopping.recipe;

import com.fherrmann.shopping.dto.Board;
import com.fherrmann.shopping.dto.DishRequest;
import com.fherrmann.shopping.model.Dish;
import com.fherrmann.shopping.model.Ingredient;
import com.fherrmann.shopping.model.Nutrition;
import com.fherrmann.shopping.recipe.ChatView.JobView;
import com.fherrmann.shopping.repository.ShoppingRepository;
import com.fherrmann.shopping.security.TokenRegistry;
import com.fherrmann.shopping.service.Categorizer;
import com.fherrmann.shopping.service.ShoppingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipeChatJobsTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T17:00:00Z"), ZoneId.of("Europe/Berlin"));
    private static final String A = "1a2b3c4d-0000-4000-8000-00000000000a";
    private static final String B = "1a2b3c4d-0000-4000-8000-00000000000b";

    private static final RecipeDraft DRAFT = new RecipeDraft("Pasta mit Feta", 2, 25,
            List.of(new Ingredient("Penne", "250 g", false), new Ingredient("Olivenöl", "2 EL", true)),
            List.of("Wasser aufsetzen.", "Penne kochen."), new Nutrition(640.0, 24.0, 78.0, 24.0), 420.0);

    @TempDir
    Path dir;

    private final CountDownLatch release = new CountDownLatch(1);
    private FakeAgent agent;
    private RecipeChatRepository repository;
    private ShoppingService shopping;
    private RecipeChatJobs jobs;

    @BeforeEach
    void setUp() {
        agent = new FakeAgent();
        repository = new RecipeChatRepository(dir.resolve("recipe-chat.json").toString(), MAPPER);
        shopping = new ShoppingService(new ShoppingRepository(dir.resolve("shopping.json").toString(), MAPPER),
                new Categorizer(), CLOCK, 90);
        jobs = jobs(40);
    }

    private final List<RecipeChatJobs> started = new ArrayList<>();

    /** Erst wenn kein Arbeitsthread mehr schreibt, darf JUnit das Verzeichnis wegraeumen. */
    @AfterEach
    void tearDown() throws InterruptedException {
        release.countDown();
        for (RecipeChatJobs each : started) {
            assertThat(each.awaitQuiet(10)).isTrue();
        }
    }

    private RecipeChatJobs jobs(int dailyLimit) {
        TokenRegistry registry = new TokenRegistry("felix:token-felix-1,joana:token-joana-2,gast:token-gast-3");
        RecipeChatJobs created = new RecipeChatJobs(agent, new RecipeChatAccess(registry, "felix,joana"),
                repository, shopping, CLOCK, dailyLimit);
        started.add(created);
        return created;
    }

    /** Ein Agent, der tut, was der Test ihm sagt, und mitschreibt. */
    private final class FakeAgent implements RecipeAgent {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<List<String>> names = new CopyOnWriteArrayList<>();
        final List<List<ChatMessage>> histories = new CopyOnWriteArrayList<>();
        volatile boolean available = true;
        volatile boolean waitForRelease;
        volatile Supplier<RecipeAnswer> answer = () -> new RecipeAnswer("Wie wäre es damit?", DRAFT);

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public RecipeAnswer answer(List<String> recipeNames, List<ChatMessage> history, String text) {
            calls.add(text);
            names.add(recipeNames);
            histories.add(history);
            if (waitForRelease) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return answer.get();
        }
    }

    /** Wartet, bis fuer die Person nichts mehr laeuft, und gibt den Stand zurueck. */
    private ChatView awaitIdle(RecipeChatJobs jobs, String user) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            ChatView view = jobs.view(user);
            if (view.job() == null || !JobView.RUNNING.equals(view.job().status())) {
                return view;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Antwort wurde nicht fertig");
    }

    private static void assertStatus(HttpStatus expected, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(expected));
    }

    @Test
    void eineNachrichtWirdAngenommenUndImHintergrundBeantwortet() throws Exception {
        shopping.createDish("joana", new DishRequest("Bolognese", List.of()));
        agent.waitForRelease = true;

        ChatView started = jobs.send("felix", "  Etwas mit Pasta und Feta  ", A);
        assertThat(started.available()).isTrue();
        assertThat(started.job().status()).isEqualTo(JobView.RUNNING);
        assertThat(started.job().elapsedSeconds()).isZero();
        assertThat(started.messages()).extracting(ChatMessage::id).containsExactly(A);
        assertThat(started.messages().get(0).role()).isEqualTo(ChatMessage.USER);
        assertThat(started.messages().get(0).text()).isEqualTo("Etwas mit Pasta und Feta");

        release.countDown();
        ChatView done = awaitIdle(jobs, "felix");
        assertThat(done.job()).isNull();
        assertThat(done.messages()).hasSize(2);
        ChatMessage reply = done.messages().get(1);
        assertThat(reply.role()).isEqualTo(ChatMessage.ASSISTANT);
        assertThat(reply.text()).isEqualTo("Wie wäre es damit?");
        assertThat(reply.draft()).isEqualTo(DRAFT);
        assertThat(reply.savedDishId()).isNull();
        assertThat(reply.id()).isNotBlank().isNotEqualTo(A);

        // Der Agent bekam die Namen der gespeicherten Rezepte, kein Gespraech davor und den getrimmten Text.
        assertThat(agent.calls).containsExactly("Etwas mit Pasta und Feta");
        assertThat(agent.names.get(0)).containsExactly("Bolognese");
        assertThat(agent.histories.get(0)).isEmpty();
        // Das Gespraech der anderen Person bleibt leer.
        assertThat(jobs.view("joana").messages()).isEmpty();
    }

    @Test
    void dasGespraechDavorGehtAlsVorgeschichteMit() throws Exception {
        jobs.send("felix", "Etwas mit Pasta", A);
        awaitIdle(jobs, "felix");
        jobs.send("felix", "mach es vegetarisch", B);
        ChatView view = awaitIdle(jobs, "felix");

        assertThat(view.messages()).hasSize(4);
        List<ChatMessage> history = agent.histories.get(1);
        assertThat(history).extracting(ChatMessage::role).containsExactly(ChatMessage.USER, ChatMessage.ASSISTANT);
        assertThat(history.get(1).draft()).isEqualTo(DRAFT);
        assertThat(agent.calls).containsExactly("Etwas mit Pasta", "mach es vegetarisch");
    }

    /** Eine Seite, die sich verheddert, soll keine Warteschlange voller Sessions aufbauen. */
    @Test
    void nurEineOffeneFrageJePerson() throws Exception {
        agent.waitForRelease = true;
        jobs.send("felix", "Etwas mit Pasta", A);

        assertStatus(HttpStatus.CONFLICT, () -> jobs.send("felix", "und noch etwas", B));
        assertStatus(HttpStatus.CONFLICT, () -> jobs.send("felix", "ohne Kennung", null));
        // Dieselbe Nachricht noch einmal ist kein Fehler - sie laeuft ja schon.
        ChatView again = jobs.send("felix", "Etwas mit Pasta", A);
        assertThat(again.job().status()).isEqualTo(JobView.RUNNING);
        assertThat(again.messages()).hasSize(1);
        // Die Grenze gilt je Person.
        assertThat(jobs.send("joana", "Suppe", B).job().status()).isEqualTo(JobView.RUNNING);

        release.countDown();
        assertThat(awaitIdle(jobs, "felix").messages()).hasSize(2);
        assertThat(awaitIdle(jobs, "joana").messages()).hasSize(2);
        assertThat(agent.calls).containsExactly("Etwas mit Pasta", "Suppe");
        // Wieder frei, sobald die eigene Antwort da ist.
        assertThat(jobs.send("felix", "jetzt wieder", null).job().status()).isEqualTo(JobView.RUNNING);
    }

    /** Aus dem Postausgang nachgesendet, obwohl die Antwort laengst da ist: nichts passiert. */
    @Test
    void dieselbeKennungHaengtNichtsAnUndFragtNichtNeu() throws Exception {
        jobs.send("felix", "Etwas mit Pasta", A);
        awaitIdle(jobs, "felix");

        ChatView again = jobs.send("felix", "Etwas mit Pasta", A);
        assertThat(again.job()).isNull();
        assertThat(again.messages()).hasSize(2);
        assertThat(agent.calls).hasSize(1);
        assertThat(repository.usage("felix", LocalDate.now(CLOCK))).isEqualTo(1);
    }

    /** Scheitert die Antwort, kommt die Meldung an - und dieselbe Nachricht laesst sich neu anfordern. */
    @Test
    void eineGescheiterteAntwortLaesstSichNeuAnfordern() throws Exception {
        agent.answer = () -> {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Die Antwort hat länger als 120 Sekunden gedauert.");
        };
        jobs.send("felix", "Etwas mit Pasta", A);
        ChatView failed = awaitIdle(jobs, "felix");
        assertThat(failed.job().status()).isEqualTo(JobView.FAILED);
        assertThat(failed.job().error()).isEqualTo("Die Antwort hat länger als 120 Sekunden gedauert.");
        assertThat(failed.job().elapsedSeconds()).isNull();
        assertThat(failed.messages()).extracting(ChatMessage::id).containsExactly(A);

        agent.answer = () -> new RecipeAnswer("Jetzt aber.", null);
        agent.waitForRelease = true;
        ChatView retried = jobs.send("felix", "Etwas mit Pasta", A);
        assertThat(retried.job().status()).isEqualTo(JobView.RUNNING);
        assertThat(retried.messages()).as("neu angefordert, nicht noch einmal angehaengt").hasSize(1);
        release.countDown();
        ChatView done = awaitIdle(jobs, "felix");
        assertThat(done.job()).isNull();
        assertThat(done.messages()).extracting(ChatMessage::text).containsExactly("Etwas mit Pasta", "Jetzt aber.");
        assertThat(done.messages().get(1).draft()).isNull();
        // Beide Versuche gingen mit demselben Text und ohne Vorgeschichte hinaus - und beide zaehlen.
        assertThat(agent.calls).containsExactly("Etwas mit Pasta", "Etwas mit Pasta");
        assertThat(agent.histories.get(1)).isEmpty();
        assertThat(repository.usage("felix", LocalDate.now(CLOCK))).isEqualTo(2);
    }

    @Test
    void einUnerwarteterFehlerWirdEinSchlichterSatz() throws Exception {
        agent.answer = () -> {
            throw new IllegalStateException("kaputt");
        };
        jobs.send("felix", "Etwas mit Pasta", A);
        assertThat(awaitIdle(jobs, "felix").job().error()).isEqualTo("Die Antwort ist fehlgeschlagen.");
        // Nach einem Fehler geht auch eine neue Nachricht - sie wartet nicht auf die alte.
        agent.answer = () -> new RecipeAnswer("Gern.", null);
        jobs.send("felix", "dann eben Suppe", B);
        assertThat(awaitIdle(jobs, "felix").messages()).extracting(ChatMessage::text)
                .containsExactly("Etwas mit Pasta", "dann eben Suppe", "Gern.");
    }

    /** Der Dienst lief neu: der Auftrag ist weg, die Frage steht ohne Antwort da und laesst sich neu schicken. */
    @Test
    void nachEinemNeustartStehtDieFrageOhneAntwortDa() throws Exception {
        agent.waitForRelease = true;
        jobs.send("felix", "Etwas mit Pasta", A);

        RecipeChatJobs restarted = jobs(40);
        ChatView view = restarted.view("felix");
        assertThat(view.job()).isNull();
        assertThat(view.messages()).extracting(ChatMessage::id).containsExactly(A);

        agent.waitForRelease = false;
        restarted.send("felix", "Etwas mit Pasta", A);
        ChatView done = awaitIdle(restarted, "felix");
        assertThat(done.messages()).hasSize(2);
        assertThat(done.messages().get(1).role()).isEqualTo(ChatMessage.ASSISTANT);
    }

    /** "Neu" leert das Gespraech - eine Antwort, die noch unterwegs war, kommt nicht mehr hinein. */
    @Test
    void neuVerwirftEineAntwortDieNochUnterwegsIst() throws Exception {
        agent.waitForRelease = true;
        jobs.send("felix", "Etwas mit Pasta", A);

        ChatView fresh = jobs.reset("felix");
        assertThat(fresh.messages()).isEmpty();
        assertThat(fresh.job()).isNull();

        release.countDown();
        // Der Arbeitsthread ist frei, sobald eine zweite Person ihre Antwort hat.
        agent.waitForRelease = false;
        jobs.send("joana", "Suppe", B);
        awaitIdle(jobs, "joana");
        assertThat(jobs.view("felix").messages()).isEmpty();
        assertThat(agent.calls).containsExactly("Etwas mit Pasta", "Suppe");
    }

    @Test
    void dasTageslimitGiltJePerson() throws Exception {
        RecipeChatJobs limited = jobs(2);
        limited.send("felix", "eins", null);
        awaitIdle(limited, "felix");
        limited.send("felix", "zwei", null);
        awaitIdle(limited, "felix");

        assertStatus(HttpStatus.TOO_MANY_REQUESTS, () -> limited.send("felix", "drei", null));
        assertThat(limited.view("felix").messages()).hasSize(4);
        assertThat(agent.calls).hasSize(2);
        assertThat(limited.send("joana", "eins", null).job().status()).isEqualTo(JobView.RUNNING);
        awaitIdle(limited, "joana");
    }

    /** Geprueft wird vor dem Start - nichts davon erreicht den Agenten. */
    @Test
    void pruefungenVorDemStart() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> jobs.send("felix", "   ", null));
        assertStatus(HttpStatus.BAD_REQUEST, () -> jobs.send("felix", null, null));
        assertStatus(HttpStatus.BAD_REQUEST, () -> jobs.send("felix", "x".repeat(RecipeChatJobs.MAX_TEXT + 1), null));
        assertStatus(HttpStatus.BAD_REQUEST, () -> jobs.send("felix", "Pasta", "keine-uuid"));
        assertStatus(HttpStatus.BAD_REQUEST, () -> jobs.send("felix", "Pasta", A.toUpperCase()));

        // Nicht freigeschaltet: der Chat ist fuer die Person gar nicht da.
        assertStatus(HttpStatus.FORBIDDEN, () -> jobs.send("gast", "Pasta", null));
        assertStatus(HttpStatus.FORBIDDEN, () -> jobs.saveDraft("gast", "m1"));
        assertThat(jobs.view("gast")).isEqualTo(new ChatView(false, List.of(), null));
        assertThat(jobs.reset("gast").available()).isFalse();

        // Kein Agent eingerichtet: sichtbar als "nicht verfuegbar", Absenden ist ein 503.
        agent.available = false;
        assertThat(jobs.view("felix").available()).isFalse();
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, () -> jobs.send("felix", "Pasta", null));

        assertThat(agent.calls).isEmpty();
        assertThat(repository.conversation("felix").messages()).isEmpty();
        assertThat(repository.usage("felix", LocalDate.now(CLOCK))).isZero();
    }

    @Test
    void einEntwurfWirdGenauEinmalZumRezept() throws Exception {
        jobs.send("felix", "Etwas mit Pasta", A);
        String messageId = awaitIdle(jobs, "felix").messages().get(1).id();

        Board board = jobs.saveDraft("felix", messageId);
        assertThat(board.dishes()).hasSize(1);
        Dish dish = board.dishes().get(0);
        assertThat(dish.name()).isEqualTo("Pasta mit Feta");
        assertThat(dish.source()).isEqualTo("chat");
        assertThat(dish.createdBy()).isEqualTo("felix");
        assertThat(dish.portions()).isEqualTo(2);
        assertThat(dish.minutes()).isEqualTo(25);
        assertThat(dish.ingredients()).isEqualTo(DRAFT.ingredients());
        assertThat(dish.steps()).isEqualTo(DRAFT.steps());
        assertThat(dish.nutrition()).isEqualTo(DRAFT.nutrition());
        assertThat(dish.gramsPerPortion()).isEqualTo(420.0);
        assertThat(jobs.view("felix").messages().get(1).savedDishId()).isEqualTo(dish.id());

        // Noch einmal gedrueckt: kein zweites Rezept, kein Fehler.
        assertThat(jobs.saveDraft("felix", messageId).dishes()).hasSize(1);

        // Den Entwurf einer anderen Person gibt es fuer einen nicht; eine Nachricht ohne Entwurf auch nicht.
        assertStatus(HttpStatus.NOT_FOUND, () -> jobs.saveDraft("joana", messageId));
        assertStatus(HttpStatus.NOT_FOUND, () -> jobs.saveDraft("felix", A));
        assertStatus(HttpStatus.NOT_FOUND, () -> jobs.saveDraft("felix", "gibt-es-nicht"));
    }

    /** Heisst schon ein Rezept so, scheitert das Speichern - und der Entwurf bleibt speicherbar. */
    @Test
    void einVergebenerNameLaesstDenEntwurfSpeicherbar() throws Exception {
        String existing = shopping.createDish("joana", new DishRequest("pasta mit feta", List.of())).dishes().get(0).id();
        jobs.send("felix", "Etwas mit Pasta", A);
        String messageId = awaitIdle(jobs, "felix").messages().get(1).id();

        assertStatus(HttpStatus.CONFLICT, () -> jobs.saveDraft("felix", messageId));
        assertThat(jobs.view("felix").messages().get(1).savedDishId()).isNull();

        shopping.deleteDish("joana", existing);
        assertThat(jobs.saveDraft("felix", messageId).dishes()).hasSize(1);
    }

    @Test
    void derAufraeumerLaesstFrischeGespraecheStehen() throws Exception {
        jobs.send("felix", "Etwas mit Pasta", A);
        awaitIdle(jobs, "felix");
        jobs.cleanup();
        assertThat(jobs.view("felix").messages()).hasSize(2);
        assertThat(repository.usage("felix", LocalDate.now(CLOCK))).isEqualTo(1);
        assertThat(new ArrayList<>(agent.calls)).hasSize(1);
    }
}
