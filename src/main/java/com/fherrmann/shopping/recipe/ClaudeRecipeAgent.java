package com.fherrmann.shopping.recipe;

import com.fherrmann.shopping.model.Ingredient;
import com.fherrmann.shopping.model.Nutrition;
import com.fherrmann.shopping.service.QuantityParser;
import com.fherrmann.shopping.service.ShoppingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Beantwortet den Rezept-Chat durch eine <b>eigene Claude-Code-Session auf dem
 * Server</b> - dieselbe Bauart wie der Story-Agent des Kalorienzaehlers: ein
 * Verzeichnis neben dem Repo ({@code ~/services/recipe-agent}, Vorlage
 * {@code deploy/recipe-agent}), gestartet ueber eine einzelne sudo-Ausnahme als
 * {@code flexii}, ohne ein einziges Werkzeug. Die Session bekommt einen Text und
 * gibt ein JSON-Objekt zurueck: ein paar Saetze und, wenn es passt, ein Rezept.
 *
 * <p>Der Auftrag geht ueber die Standardeingabe: er enthaelt, was Menschen getippt
 * haben, so gibt es nichts zu escapen und keine Laengengrenze einer Kommandozeile.
 * Alles, was von Menschen oder aus frueheren Antworten stammt, steht in Klammern
 * ({@code <rezepte>}, {@code <verlauf>}, {@code <entwurf>}, {@code <anfrage>}), die
 * der Text selbst nicht schliessen kann - die {@code CLAUDE.md} des Agenten nennt
 * sie Zitat, nie Anweisung.
 *
 * <p>Gelesen wird nachsichtig: ein Entwurf ist ein Vorschlag, den die Person vor dem
 * Speichern sieht. Was nicht passt, wird gekuerzt oder weggelassen, nicht abgelehnt.
 */
@Component
public class ClaudeRecipeAgent implements RecipeAgent {

    private static final Logger log = LoggerFactory.getLogger(ClaudeRecipeAgent.class);

    /** Mehr schreibt keine Session, die ein Rezept liefert - der Rest wird verworfen. */
    private static final int MAX_OUTPUT = 256 * 1024;

    /** So viele Nachrichten des Gespraechs gehen mit - mehr braucht kein "mach es vegetarisch". */
    static final int HISTORY = 12;
    static final int MAX_REPLY = 600;
    static final int MAX_NAMES = 200;
    static final int MAX_QUANTITY = 40;

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration PROBE_RETRY = Duration.ofMinutes(5);

    /** Kein Text von aussen soll eine der Klammern schliessen oder eine neue oeffnen koennen. */
    private static final Pattern TAG = Pattern.compile("(?i)<\\s*(/?)\\s*(rezepte|verlauf|entwurf|anfrage)\\s*>");

    private static final Pattern FRACTION = Pattern.compile("(?:(\\d+)\\s+)?(\\d+)\\s*/\\s*(\\d+)");
    /** "1. ", "2) ", "Schritt 3: ", "- " vor einem Schritt - die Oberflaechen zaehlen selbst. */
    private static final Pattern STEP_PREFIX =
            Pattern.compile("^\\s*(?:(?:schritt\\s+)?\\d{1,2}\\s*[.):]\\s+|[-*•]\\s+)", Pattern.CASE_INSENSITIVE);

    private final List<String> command;
    private final long timeoutSeconds;
    private final boolean probing;
    private final ObjectMapper objectMapper;

    /** {@code null}, solange die Probe noch laeuft. */
    private volatile Boolean probed;
    private volatile Instant probedAt = Instant.EPOCH;
    private final AtomicBoolean probeRunning = new AtomicBoolean();

    @Autowired
    public ClaudeRecipeAgent(
            @Value("${shopping.recipe-agent.command:}") String command,
            @Value("${shopping.recipe-agent.timeout-seconds:120}") long timeoutSeconds,
            @Value("${shopping.recipe-agent.probe:true}") boolean probe,
            ObjectMapper objectMapper) {
        this.command = command == null || command.isBlank()
                ? List.of()
                : List.of(command.trim().split("\\s+"));
        this.timeoutSeconds = timeoutSeconds;
        this.probing = probe;
        this.objectMapper = objectMapper;
        if (this.command.isEmpty()) {
            log.info("shopping.recipe-agent.command ist leer - Rezepte ohne Chat.");
        } else if (probe) {
            startProbe();
        }
    }

    /**
     * Eingerichtet heisst: ein Kommando ist gesetzt <b>und</b> es laesst sich aus dem
     * Dienst heraus starten. Das zweite prueft {@code run-agent.sh --version} einmal
     * beim Start - es beweist sudo, Nutzer und Binary unter den echten Bedingungen der
     * Unit, ohne eine (kostende) Session. Die Probe im Setup-Skript laeuft ausserhalb
     * der Unit und merkt nicht, wenn {@code NoNewPrivileges} sudo dort sperrt.
     */
    @Override
    public boolean isAvailable() {
        if (command.isEmpty()) {
            return false;
        }
        if (!probing) {
            return true;
        }
        Boolean state = probed;
        if (Boolean.TRUE.equals(state)) {
            return true;
        }
        // Gescheitert heisst nicht fuer immer: alle paar Minuten ein neuer Versuch.
        if (state != null && probedAt.plus(PROBE_RETRY).isBefore(Instant.now())) {
            startProbe();
        }
        return false;
    }

    private void startProbe() {
        if (!probeRunning.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("recipe-agent-probe").start(() -> {
            try {
                probed = probe();
            } finally {
                probedAt = Instant.now();
                probeRunning.set(false);
            }
        });
    }

    /** {@code <kommando> --version}: laeuft durch, wenn der Dienst den Agenten starten darf. */
    boolean probe() {
        List<String> probe = new ArrayList<>(command);
        probe.add("--version");
        Process process;
        try {
            process = new ProcessBuilder(probe).redirectErrorStream(true).start();
        } catch (IOException e) {
            log.warn("Rezept-Agent nicht startbar - Rezepte ohne Chat: {}", e.getMessage());
            return false;
        }
        FutureTask<String> output = new FutureTask<>(() -> readLimited(process.getInputStream()));
        Thread.ofVirtual().name("recipe-agent-probe-output").start(output);
        try {
            process.getOutputStream().close();
            if (!process.waitFor(PROBE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                stop(process);
                log.warn("Rezept-Agent: die Probe (--version) endet nicht - Rezepte ohne Chat.");
                return false;
            }
            String text = output.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                log.warn("Rezept-Agent: die Probe (--version) endet mit {} - Rezepte ohne Chat. "
                        + "Steht NoNewPrivileges noch in der Unit? {}", process.exitValue(), tail(text));
                return false;
            }
            log.info("Rezept-Agent bereit ({}).", text.strip().lines().findFirst().orElse("ohne Ausgabe"));
            return true;
        } catch (InterruptedException e) {
            stop(process);
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | ExecutionException | TimeoutException e) {
            stop(process);
            log.warn("Rezept-Agent: die Probe ist gescheitert - Rezepte ohne Chat: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public RecipeAnswer answer(List<String> recipeNames, List<ChatMessage> history, String text) {
        if (!isAvailable()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Der Rezept-Chat ist auf diesem Server nicht eingerichtet.");
        }
        return parse(run(prompt(recipeNames, history, text)));
    }

    // MARK: - Der Auftrag

    /**
     * Der veraenderliche Teil des Auftrags. Was immer gilt, steht in der
     * {@code CLAUDE.md} des Agent-Verzeichnisses. Keine Namen von Personen - wer fragt,
     * tut fuer ein Rezept nichts zur Sache.
     */
    String prompt(List<String> recipeNames, List<ChatMessage> history, String text) {
        StringBuilder out = new StringBuilder();
        out.append("<rezepte>\n");
        int count = 0;
        for (String name : recipeNames == null ? List.<String>of() : recipeNames) {
            if (count++ >= MAX_NAMES) {
                break;
            }
            out.append(quote(clamp(oneLine(name), 120))).append('\n');
        }
        out.append("</rezepte>\n\n");

        List<ChatMessage> all = history == null ? List.of() : history;
        List<ChatMessage> recent = all.subList(Math.max(0, all.size() - HISTORY), all.size());
        out.append("<verlauf>\n");
        RecipeDraft latest = null;
        for (ChatMessage message : recent) {
            boolean own = ChatMessage.USER.equals(message.role());
            out.append(own ? "Person: " : "Du: ").append(quote(oneLine(message.text() == null ? "" : message.text())));
            if (message.draft() != null) {
                latest = message.draft();
                out.append(" [Rezeptvorschlag: ").append(quote(oneLine(message.draft().name()))).append(']');
            }
            out.append('\n');
        }
        out.append("</verlauf>\n\n");

        // Nur der juengste Vorschlag als Ganzes: "mach es vegetarisch" meint ihn, und
        // jeder weitere kostete nur Platz.
        if (latest != null) {
            out.append("<entwurf>\n").append(quote(objectMapper.writeValueAsString(latest))).append("\n</entwurf>\n\n");
        }

        // Die neue Nachricht kommt zuletzt und klar abgegrenzt: alles dazwischen ist Zitat.
        out.append("<anfrage>\n").append(quote(text == null ? "" : text.strip())).append("\n</anfrage>\n");
        return out.toString();
    }

    /** Aus "<anfrage>" im Text wird "[anfrage]" - lesbar, aber keine Klammer mehr. */
    private static String quote(String value) {
        return TAG.matcher(value).replaceAll("[$1$2]");
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    // MARK: - Die Session

    /** Startet die Session, schiebt den Auftrag hinein und gibt aus, was zurueckkam. */
    private String run(String prompt) {
        Process process;
        try {
            // Fehlerausgabe mit einsammeln: laeuft etwas schief, steht der Grund
            // sonst nirgends.
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            log.warn("Rezept-Agent nicht startbar: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Der Rezept-Chat ist gerade nicht verfügbar.", e);
        }

        // Die Ausgabe nebenher lesen, nicht erst nach dem Warten: sonst haelt ein
        // Prozess, der nicht endet, auch das Lesen fest - und das Zeitlimit griffe nie.
        FutureTask<String> output = new FutureTask<>(() -> readLimited(process.getInputStream()));
        Thread.ofVirtual().name("recipe-agent-output").start(output);

        try {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // Die Session hat die Eingabe vorzeitig geschlossen - ihre Ausgabe sagt, warum.
                log.debug("Rezept-Agent nahm die Eingabe nicht vollstaendig an", e);
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                stop(process);
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                        "Die Antwort hat länger als " + timeoutSeconds + " Sekunden gedauert.");
            }
            String text = output.get(10, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                log.warn("Rezept-Agent beendet mit {}: {}", process.exitValue(), tail(text));
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Claude konnte nicht antworten.");
            }
            return text;
        } catch (InterruptedException e) {
            stop(process);
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Die Antwort wurde abgebrochen.", e);
        } catch (ExecutionException | TimeoutException e) {
            stop(process);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Claude hat nicht geantwortet.", e);
        }
    }

    /**
     * Erst SIGTERM, dann SIGKILL. Die Reihenfolge zaehlt: der Prozess ist {@code sudo},
     * und sudo reicht SIGTERM an die Session weiter - SIGKILL traefe nur sudo, die
     * Session liefe als Waise zu Ende und kostete trotzdem.
     */
    private static void stop(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static String readLimited(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_OUTPUT);
        in.transferTo(OutputStream.nullOutputStream());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // MARK: - Die Antwort

    /**
     * Aus der Ausgabe Antwort und Rezept herausschaelen. Zwei Huellen sind moeglich:
     * der JSON-Umschlag von {@code claude --output-format json} (die Antwort steckt in
     * {@code result}, mit {@code --json-schema} in {@code structured_output}) oder die
     * blanke Antwort. Beides wird akzeptiert, damit ein Wechsel im Skript nichts bricht.
     */
    RecipeAnswer parse(String output) {
        JsonNode outer = tryReadJson(output);
        if (outer == null) {
            // Eine Warnung auf stderr vor dem Umschlag - stderr laeuft mit ein.
            outer = tryReadJson(extractJsonObject(output));
        }
        String payload = output;
        JsonNode node = null;
        boolean enveloped = outer != null && outer.isObject()
                && (outer.has("result") || outer.has("structured_output"));
        if (enveloped) {
            String subtype = outer.path("subtype").asString("success");
            if (outer.path("is_error").asBoolean(false) || !"success".equals(subtype)) {
                log.warn("Rezept-Agent meldet einen Fehler ({}): {}", subtype, tail(outer.path("result").asString("")));
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Claude konnte nicht antworten.");
            }
            if (outer.has("total_cost_usd")) {
                log.info("Rezept-Antwort fertig ({} ms, {} USD)",
                        outer.path("duration_ms").asLong(0), outer.path("total_cost_usd").asDouble(0));
            }
            if (outer.path("structured_output").isObject()) {
                node = outer.path("structured_output");
            }
            payload = outer.path("result").asString("");
        }
        if (node == null) {
            node = tryReadJson(extractJsonObject(payload));
        }
        if (node == null || !node.isObject()) {
            // Das Modell hat in Saetzen statt im Objekt geantwortet ("Was hast du denn
            // da?"). Im Umschlag ist das eine Antwort ohne Rezept; ohne Umschlag ist es
            // eher eine Fehlermeldung der Kommandozeile - die zeigt niemand im Chat.
            String prose = payload == null ? "" : payload.strip();
            if (enveloped && !prose.isEmpty() && prose.indexOf('{') < 0) {
                return new RecipeAnswer(clamp(prose, MAX_REPLY), null);
            }
            log.warn("Unverwertbare Antwort des Rezept-Agenten: {}", tail(output));
            throw unreadable();
        }

        String reply = clamp(node.path("reply").asString("").strip(), MAX_REPLY);
        JsonNode recipe = node.path("recipe");
        // Das Rezept allein, ohne die Huelle drumherum, ist auch eines.
        if (!recipe.isObject() && node.has("name") && node.has("ingredients")) {
            recipe = node;
        }
        RecipeDraft draft = recipe.isObject() ? draft(recipe) : null;
        if (reply.isEmpty() && draft == null) {
            log.warn("Antwort des Rezept-Agenten ohne Text und ohne Rezept: {}", tail(output));
            throw unreadable();
        }
        return new RecipeAnswer(reply, draft);
    }

    private static ResponseStatusException unreadable() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Die Antwort von Claude war nicht lesbar.");
    }

    /**
     * Das Rezept in der Form, in der es gespeichert wuerde - gekuerzt auf die Grenzen,
     * die auch beim Speichern gelten. Ohne Namen oder ohne Zutaten ist es keines.
     */
    private RecipeDraft draft(JsonNode node) {
        String name = clamp(oneLine(node.path("name").asString("")), ShoppingService.MAX_NAME);
        List<Ingredient> ingredients = ingredients(node.path("ingredients"));
        if (name.isEmpty() || ingredients.isEmpty()) {
            return null;
        }
        Double grams = decimalIn(node.path("gramsPerPortion"), ShoppingService.MAX_GRAMS_PER_PORTION);
        if (grams != null && grams < 1) {
            grams = null;
        }
        Nutrition nutrition = nutrition(node.path("nutrition"));
        // Naehrwerte ohne Portionsgewicht, oder solche, die der Kalorienzaehler nie
        // annaehme: weg damit, das Rezept bleibt.
        if (nutrition != null && (grams == null || !ShoppingService.fitsFood(nutrition, grams))) {
            nutrition = null;
        }
        return new RecipeDraft(name,
                integerIn(node.path("portions"), ShoppingService.MAX_PORTIONS),
                integerIn(node.path("minutes"), ShoppingService.MAX_MINUTES),
                ingredients, steps(node.path("steps")), nutrition, grams);
    }

    private static List<Ingredient> ingredients(JsonNode list) {
        List<Ingredient> result = new ArrayList<>();
        for (JsonNode entry : list) {
            if (result.size() >= ShoppingService.MAX_INGREDIENTS) {
                break;
            }
            if (entry.isString()) {
                // "250 g Penne" als blosser Text: der Leser der Liste trennt Menge und Name.
                QuantityParser.Split split = QuantityParser.resolve(oneLine(entry.asString()), null);
                if (!split.name().isEmpty()) {
                    result.add(new Ingredient(clamp(split.name(), ShoppingService.MAX_NAME), split.quantity(), false));
                }
                continue;
            }
            String name = clamp(oneLine(entry.path("name").asString("")), ShoppingService.MAX_NAME);
            if (name.isEmpty()) {
                continue;
            }
            String quantity;
            if (entry.has("amount") || entry.has("unit")) {
                quantity = QuantityParser.compose(number(entry.path("amount")),
                        entry.path("unit").isString() ? oneLine(entry.path("unit").asString()) : null);
            } else {
                // Die Form des Rumpfs statt der des Vertrags - auch gut.
                String text = oneLine(entry.path("quantity").asString(""));
                quantity = text.isEmpty() ? null : QuantityParser.resolve(name, text).quantity();
            }
            if (quantity != null && quantity.length() > MAX_QUANTITY) {
                quantity = clamp(quantity, MAX_QUANTITY);
            }
            result.add(new Ingredient(name, quantity, entry.path("pantry").asBoolean(false)));
        }
        return result;
    }

    /** Eine Liste - oder ein Text mit einem Schritt je Zeile. Die Zaehlung davor faellt weg. */
    private static List<String> steps(JsonNode node) {
        List<String> lines = new ArrayList<>();
        if (node.isString()) {
            lines.addAll(node.asString().lines().toList());
        } else {
            for (JsonNode entry : node) {
                lines.add(entry.asString(""));
            }
        }
        List<String> steps = new ArrayList<>();
        for (String line : lines) {
            String step = oneLine(STEP_PREFIX.matcher(line).replaceFirst(""));
            if (!step.isEmpty() && steps.size() < ShoppingService.MAX_STEPS) {
                steps.add(clamp(step, ShoppingService.MAX_STEP));
            }
        }
        return steps;
    }

    /** Alle vier oder keiner, auf eine Nachkommastelle. */
    private static Nutrition nutrition(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        Double kcal = decimalIn(node.path("kcal"), 20_000);
        Double protein = decimalIn(first(node, "proteinG", "protein"), 2_000);
        Double carbs = decimalIn(first(node, "carbsG", "carbs"), 2_000);
        Double fat = decimalIn(first(node, "fatG", "fat"), 2_000);
        if (kcal == null || protein == null || carbs == null || fat == null) {
            return null;
        }
        return new Nutrition(kcal, protein, carbs, fat);
    }

    private static JsonNode first(JsonNode node, String name, String alternative) {
        return node.has(name) ? node.path(name) : node.path(alternative);
    }

    /** Eine Zahl von 0 bis {@code max}, auf eine Nachkommastelle; 0 selbst zaehlt (0 g Fett). */
    private static Double decimalIn(JsonNode node, int max) {
        BigDecimal value = number(node);
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.valueOf(max)) > 0) {
            return null;
        }
        return value.setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    /** Eine ganze Zahl von 1 bis {@code max}. */
    private static Integer integerIn(JsonNode node, int max) {
        BigDecimal value = number(node);
        if (value == null) {
            return null;
        }
        int rounded = value.setScale(0, RoundingMode.HALF_UP).intValue();
        return rounded < 1 || rounded > max ? null : rounded;
    }

    private static BigDecimal number(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        return node.isString() ? amount(node.asString()) : null;
    }

    /** Eine Menge, auch als Text: "1,5", "1/2", "1 1/2", "½". */
    static BigDecimal amount(String raw) {
        String text = raw == null ? "" : raw.strip();
        switch (text) {
            case "½" -> {
                return new BigDecimal("0.5");
            }
            case "¼" -> {
                return new BigDecimal("0.25");
            }
            case "¾" -> {
                return new BigDecimal("0.75");
            }
            default -> {
            }
        }
        Matcher fraction = FRACTION.matcher(text);
        if (fraction.matches()) {
            BigDecimal denominator = new BigDecimal(fraction.group(3));
            if (denominator.signum() == 0) {
                return null;
            }
            BigDecimal whole = fraction.group(1) == null ? BigDecimal.ZERO : new BigDecimal(fraction.group(1));
            return whole.add(new BigDecimal(fraction.group(2)).divide(denominator, 3, RoundingMode.HALF_UP));
        }
        try {
            return new BigDecimal(text.replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Kuerzt am letzten Wortende vor der Grenze und markiert es mit einer Ellipse. */
    static String clamp(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        String cut = value.substring(0, max - 1);
        int space = cut.lastIndexOf(' ');
        if (space > max / 2) {
            cut = cut.substring(0, space);
        }
        return cut.stripTrailing() + "…";
    }

    private JsonNode tryReadJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Das aeusserste JSON-Objekt aus einem Text schneiden. Ein Modell haengt gern einen
     * Satz davor oder packt die Antwort in einen Codeblock; beides ist harmlos, solange
     * man das Objekt findet.
     */
    private static String extractJsonObject(String value) {
        if (value == null) {
            return null;
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start >= 0 && end > start ? value.substring(start, end + 1) : value;
    }

    private static String tail(String value) {
        String trimmed = value == null ? "" : value.strip();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(trimmed.length() - 500);
    }
}
