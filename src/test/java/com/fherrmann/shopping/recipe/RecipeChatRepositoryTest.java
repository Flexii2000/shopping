package com.fherrmann.shopping.recipe;

import com.fherrmann.shopping.model.Ingredient;
import com.fherrmann.shopping.model.Nutrition;
import com.fherrmann.shopping.recipe.RecipeChatRepository.Conversation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeChatRepositoryTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Instant NOW = Instant.parse("2026-11-10T17:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    @TempDir
    Path dir;

    private RecipeChatRepository repository() {
        return new RecipeChatRepository(dir.resolve("recipe-chat.json").toString(), MAPPER);
    }

    private static ChatMessage user(String id, String text) {
        return new ChatMessage(id, ChatMessage.USER, text, NOW, null, null);
    }

    private static final RecipeDraft DRAFT = new RecipeDraft("Pasta mit Feta", 2, 25,
            List.of(new Ingredient("Penne", "250 g", false), new Ingredient("Olivenöl", "2 EL", true)),
            List.of("Kochen."), new Nutrition(640.0, 24.0, 78.0, 24.0), 420.0);

    @Test
    void ohneDateiIstDasGespraechLeer() {
        Conversation conversation = repository().conversation("felix");
        assertThat(conversation.messages()).isEmpty();
        assertThat(conversation.id()).isNull();
        assertThat(repository().usage("felix", TODAY)).isZero();
        assertThat(dir.resolve("recipe-chat.json")).doesNotExist();
    }

    @Test
    void jedePersonHatIhrGespraechUndEsUeberstehtDasNeuladen() {
        RecipeChatRepository repository = repository();
        Conversation first = repository.append("felix", user("m1", "Etwas mit Pasta"), null, NOW).orElseThrow();
        repository.append("felix", new ChatMessage("m2", ChatMessage.ASSISTANT, "Wie wäre es damit?", NOW, DRAFT, null),
                first.id(), NOW);
        repository.append("joana", user("j1", "Suppe"), null, NOW);

        // Eine neue Instanz liest dieselbe Datei - so sieht es nach einem Neustart aus.
        Conversation felix = repository().conversation("felix");
        assertThat(felix.id()).isEqualTo(first.id()).isNotBlank();
        assertThat(felix.messages()).extracting(ChatMessage::id).containsExactly("m1", "m2");
        assertThat(felix.messages().get(1).draft()).isEqualTo(DRAFT);
        assertThat(felix.message("m2")).isPresent();
        assertThat(felix.message("j1")).isEmpty();
        assertThat(repository().conversation("joana").messages()).extracting(ChatMessage::text).containsExactly("Suppe");
    }

    /** Die Datei gehoert nur dem Dienst - wie die Dateien des Weight Trackers. */
    @Test
    void dieDateiIstNurFuerDenDienstLesbar() throws Exception {
        repository().append("felix", user("m1", "x"), null, NOW);
        Path file = dir.resolve("recipe-chat.json");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        try (var entries = Files.list(dir)) {
            assertThat(entries.map(p -> p.getFileName().toString())).containsExactly("recipe-chat.json");
        }
    }

    /** "Neu" gibt dem Gespraech eine neue Kennung - eine Antwort fuer das alte faellt weg. */
    @Test
    void eineAntwortFuerEinAltesGespraechFaelltWeg() {
        RecipeChatRepository repository = repository();
        String old = repository.append("felix", user("m1", "Etwas mit Pasta"), null, NOW).orElseThrow().id();

        Conversation fresh = repository.reset("felix", NOW);
        assertThat(fresh.messages()).isEmpty();
        assertThat(fresh.id()).isNotEqualTo(old);

        assertThat(repository.append("felix", user("spät", "zu spät"), old, NOW)).isEmpty();
        assertThat(repository.conversation("felix").messages()).isEmpty();
        // Und ohne Gespraech gibt es auch keines, zu dem eine Antwort gehoeren koennte.
        assertThat(repository.append("joana", user("x", "x"), "irgendeine", NOW)).isEmpty();
    }

    @Test
    void mehrAlsDreissigNachrichtenBleibenNicht() {
        RecipeChatRepository repository = repository();
        for (int i = 0; i < RecipeChatRepository.MAX_MESSAGES + 5; i++) {
            repository.append("felix", user("m" + i, "Nachricht " + i), null, NOW);
        }
        List<ChatMessage> messages = repository.conversation("felix").messages();
        assertThat(messages).hasSize(RecipeChatRepository.MAX_MESSAGES);
        assertThat(messages.getFirst().id()).isEqualTo("m5");
        assertThat(messages.getLast().id()).isEqualTo("m34");
    }

    @Test
    void derEntwurfMerktSichWasAusIhmWurde() {
        RecipeChatRepository repository = repository();
        repository.append("felix", new ChatMessage("m2", ChatMessage.ASSISTANT, "So?", NOW, DRAFT, null), null, NOW);
        repository.markSaved("felix", "m2", "dish-1");
        repository.markSaved("joana", "m2", "dish-2");
        ChatMessage saved = repository.conversation("felix").message("m2").orElseThrow();
        assertThat(saved.savedDishId()).isEqualTo("dish-1");
        assertThat(saved.draft()).isEqualTo(DRAFT);
    }

    @Test
    void zaehltJePersonUndTag() {
        RecipeChatRepository repository = repository();
        repository.countUsage("felix", TODAY);
        repository.countUsage("felix", TODAY);
        repository.countUsage("felix", TODAY.plusDays(1));
        repository.countUsage("joana", TODAY);
        assertThat(repository().usage("felix", TODAY)).isEqualTo(2);
        assertThat(repository().usage("felix", TODAY.plusDays(1))).isEqualTo(1);
        assertThat(repository().usage("joana", TODAY)).isEqualTo(1);
        assertThat(repository().usage("joana", TODAY.minusDays(1))).isZero();
    }

    @Test
    void derAufraeumerNimmtStilleGespraecheUndAlteZaehler() {
        RecipeChatRepository repository = repository();
        repository.append("felix", user("alt", "lange her"), null, NOW.minus(Duration.ofDays(40)));
        repository.append("joana", user("neu", "gestern"), null, NOW.minus(Duration.ofDays(1)));
        repository.countUsage("felix", TODAY.minusDays(10));
        repository.countUsage("felix", TODAY);

        repository.cleanup(NOW.minus(Duration.ofDays(30)), TODAY.minusDays(7));

        assertThat(repository.conversation("felix").messages()).isEmpty();
        assertThat(repository.conversation("joana").messages()).hasSize(1);
        assertThat(repository.usage("felix", TODAY.minusDays(10))).isZero();
        assertThat(repository.usage("felix", TODAY)).isEqualTo(1);
    }

    /** Ein Verlauf, der sich nicht lesen laesst, sperrt den Chat nicht - er faengt leer an. */
    @Test
    void eineKaputteDateiFaengtLeerAn() throws Exception {
        Files.writeString(dir.resolve("recipe-chat.json"), "{\"conversations\": {\"felix\": {\"id\": ");
        RecipeChatRepository repository = repository();
        assertThat(repository.conversation("felix").messages()).isEmpty();
        repository.append("felix", user("m1", "weiter"), null, NOW);
        assertThat(repository().conversation("felix").messages()).hasSize(1);
    }

    /** Ein Feld, das eine spaetere Fassung nicht mehr kennt oder noch nicht schreibt, darf fehlen. */
    @Test
    void eineNachrichtOhneDieNeuerenFelderLiestSich() throws Exception {
        Files.writeString(dir.resolve("recipe-chat.json"), """
                {"conversations":{"felix":{"id":"c1","updatedAt":"2026-11-10T17:00:00Z","messages":[
                  {"id":"m1","role":"user","text":"Etwas mit Pasta","at":"2026-11-10T17:00:00Z"},
                  {"id":"m2","role":"assistant","text":"So?","at":"2026-11-10T17:00:30Z",
                   "draft":{"name":"Pasta","ingredients":[{"name":"Penne","quantity":"250 g"}]}}]}}}
                """);
        Conversation conversation = repository().conversation("felix");
        assertThat(conversation.messages()).hasSize(2);
        RecipeDraft draft = conversation.messages().get(1).draft();
        assertThat(draft.portions()).isNull();
        assertThat(draft.steps()).isEmpty();
        assertThat(draft.ingredients()).containsExactly(new Ingredient("Penne", "250 g", false));
        assertThat(repository().usage("felix", TODAY)).isZero();
    }
}
