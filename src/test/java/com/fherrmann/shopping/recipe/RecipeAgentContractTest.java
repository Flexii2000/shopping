package com.fherrmann.shopping.recipe;

import com.fherrmann.shopping.model.Ingredient;
import com.fherrmann.shopping.service.QuantityParser;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der Vertrag zwischen Dienst und Rezept-Agent steht in zwei Dateien: der
 * {@code CLAUDE.md}, nach der die Session arbeitet, und dem Code, der ihre Antwort
 * liest. Dieser Test haelt sie beieinander - eine Einheit, die nur eine Seite kennt,
 * wuerde stillschweigend zu Text, der sich nicht umrechnen laesst.
 */
class RecipeAgentContractTest {

    private static final Path AGENT_DIR = Path.of("deploy/recipe-agent");

    private static String claudeMd() throws Exception {
        return Files.readString(AGENT_DIR.resolve("CLAUDE.md"));
    }

    @Test
    void dieEinheitenDerAnleitungSindDieDesDienstes() throws Exception {
        String line = claudeMd().lines().map(String::strip)
                .filter(l -> l.startsWith("Erlaubte Einheiten:")).findFirst().orElseThrow();
        List<String> listed = Arrays.stream(line.substring("Erlaubte Einheiten:".length()).split(","))
                .map(String::strip).toList();
        assertThat(listed).isEqualTo(QuantityParser.units());
    }

    /** Das Beispiel der Anleitung ist genau das, was der Dienst erwartet - und es laesst sich speichern. */
    @Test
    void dasBeispielDerAnleitungLiestSichAlsRezept() throws Exception {
        String text = claudeMd();
        int start = text.indexOf("```\n{");
        int end = text.indexOf("\n```", start + 4);
        assertThat(start).as("das Beispiel im Codeblock").isPositive();
        String example = text.substring(start + 4, end);

        ClaudeRecipeAgent agent = new ClaudeRecipeAgent("", 10, false, JsonMapper.builder().findAndAddModules().build());
        RecipeAnswer answer = agent.parse(example);

        assertThat(answer.reply()).isNotBlank();
        RecipeDraft draft = answer.draft();
        assertThat(draft).isNotNull();
        assertThat(draft.name()).isEqualTo("Ofenpasta mit Feta und Tomaten");
        assertThat(draft.portions()).isEqualTo(2);
        assertThat(draft.minutes()).isEqualTo(35);
        assertThat(draft.steps()).hasSize(4);
        assertThat(draft.nutrition()).as("passt in den Kalorienzaehler").isNotNull();
        assertThat(draft.gramsPerPortion()).isEqualTo(480.0);
        assertThat(draft.ingredients()).contains(
                new Ingredient("Penne", "250 g", false),
                new Ingredient("Knoblauchzehen", "2 Stk", false),
                new Ingredient("Olivenöl", "3 EL", true),
                new Ingredient("Salz", null, true));
        // Jede Menge des Beispiels laesst sich umrechnen - so soll ein Entwurf aussehen.
        assertThat(draft.ingredients()).allSatisfy(ingredient ->
                assertThat(QuantityParser.scale(ingredient.quantity(), 4, 2).scaled()).as(ingredient.name()).isTrue());
    }

    /** Der Agent darf nichts - die Datei, die das festlegt, muss mit ins Verzeichnis. */
    @Test
    void derAgentHatKeinWerkzeug() throws Exception {
        var settings = JsonMapper.builder().build().readTree(Files.readString(AGENT_DIR.resolve(".claude/settings.json")));
        assertThat(settings.path("permissions").path("allow")).isEmpty();
        assertThat(settings.path("permissions").path("defaultMode").asString()).isEqualTo("default");
        List<String> denied = new java.util.ArrayList<>();
        for (var entry : settings.path("permissions").path("deny")) {
            denied.add(entry.asString());
        }
        assertThat(denied).contains("Bash", "Read", "Write", "Edit", "WebFetch", "WebSearch", "Task", "Agent");

        String script = Files.readString(AGENT_DIR.resolve("run-agent.sh"));
        assertThat(script).contains("--tools \"\"").contains("--strict-mcp-config")
                .contains("--no-session-persistence").contains("--output-format json");
        // Argumente von aussen gehen nie an claude weiter, und der Schalter, den neuere
        // Fassungen anders kennen, steht nicht in einem Kommando.
        assertThat(script).doesNotContain("\"$@\"").doesNotContain("$*");
        assertThat(script.lines().filter(l -> !l.strip().startsWith("#")))
                .noneMatch(l -> l.contains("--permission-mode"));
        assertThat(Files.isExecutable(AGENT_DIR.resolve("run-agent.sh"))).isTrue();
    }
}
