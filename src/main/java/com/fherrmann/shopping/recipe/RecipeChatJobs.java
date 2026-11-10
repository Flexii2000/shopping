package com.fherrmann.shopping.recipe;

import com.fherrmann.shopping.dto.Board;
import com.fherrmann.shopping.model.Dish;
import com.fherrmann.shopping.recipe.ChatView.JobView;
import com.fherrmann.shopping.recipe.RecipeChatRepository.Conversation;
import com.fherrmann.shopping.service.ShoppingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Der Rezept-Chat: nimmt Nachrichten an, laesst sie im Hintergrund beantworten und
 * haelt das Gespraech bereit. Dasselbe Muster wie die Entwuerfe des Kalorienzaehlers
 * ({@code StoryDraftJobs}): das Absenden antwortet sofort, die Seite fragt nach. Eine
 * Claude-Session dauert eine halbe Minute und mehr, und eine so lange offene Anfrage
 * scheitert an jeder Zwischenstation mit eigenem Timeout (nginx: 60 s), ohne
 * verwertbaren Fehler.
 *
 * <p>Nachgefragt wird ueber das Gespraech selbst ({@code GET /api/chat}), nicht ueber
 * eine Auftragsnummer: die Antwort haengt der Arbeitsthread an den Verlauf, der
 * Auftrag traegt nur "laeuft" oder "gescheitert". So uebersteht die Seite einen
 * Neustart des Dienstes - der Auftrag ist dann weg, die eigene Nachricht steht
 * ohne Antwort da, und die Oberflaeche bietet an, sie noch einmal zu schicken.
 *
 * <p>Was eine Session kostet (Felix' Claude-Kontingent), haelt in Grenzen: die
 * Freigabe je Person ({@link RecipeChatAccess}), <b>ein</b> Arbeitsthread fuer alle,
 * hoechstens eine offene Frage je Person, ein Tageslimit, die Laenge der Nachricht
 * und das Zeitlimit der Session.
 */
@Component
public class RecipeChatJobs {

    private static final Logger log = LoggerFactory.getLogger(RecipeChatJobs.class);

    static final int MAX_TEXT = 1000;
    /** Gespraeche, in denen so lange nichts passiert ist, raeumt der naechtliche Lauf weg. */
    private static final Duration IDLE = Duration.ofDays(30);

    /** Kennungen, die eine App vergibt: kleingeschriebene UUIDs - wie bei den Eintraegen. */
    private static final Pattern CLIENT_ID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final RecipeAgent agent;
    private final RecipeChatAccess access;
    private final RecipeChatRepository repository;
    private final ShoppingService shopping;
    private final Clock clock;
    private final int dailyLimit;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "recipe-chat");
        thread.setDaemon(true);
        return thread;
    });
    /** Je Person der juengste Auftrag. Nur im Speicher - nach einem Neustart weg. */
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public RecipeChatJobs(RecipeAgent agent, RecipeChatAccess access, RecipeChatRepository repository,
                          ShoppingService shopping, Clock clock,
                          @Value("${shopping.recipe-agent.daily-limit:40}") int dailyLimit) {
        this.agent = agent;
        this.access = access;
        this.repository = repository;
        this.shopping = shopping;
        this.clock = clock;
        this.dailyLimit = dailyLimit;
    }

    /** Veraenderliche Felder, weil der Arbeitsthread sie nachtraegt. */
    private static final class Job {
        private final String conversationId;
        private final Instant startedAt;
        private volatile String status = JobView.RUNNING;
        private volatile String error;

        private Job(String conversationId, Instant startedAt) {
            this.conversationId = conversationId;
            this.startedAt = startedAt;
        }

        private boolean isRunning() {
            return JobView.RUNNING.equals(status);
        }
    }

    /** Das Gespraech der Person und ob gerade eine Antwort entsteht. Auch die Nachfrage. */
    public ChatView view(String me) {
        if (!access.allows(me)) {
            return new ChatView(false, List.of(), null);
        }
        Job job = jobs.get(me);
        // Erst den Stand lesen, dann das Gespraech: der Arbeitsthread haengt die Antwort
        // an, bevor er den Auftrag als fertig meldet - nie "fertig" ohne Antwort.
        JobView jobView = null;
        if (job != null) {
            String status = job.status;
            if (JobView.RUNNING.equals(status)) {
                jobView = new JobView(JobView.RUNNING, Duration.between(job.startedAt, clock.instant()).toSeconds(), null);
            } else if (JobView.FAILED.equals(status)) {
                jobView = new JobView(JobView.FAILED, null, job.error);
            }
        }
        return new ChatView(agent.isAvailable(), repository.conversation(me).messages(), jobView);
    }

    /**
     * Nimmt eine Nachricht an und gibt sofort zurueck. Geprueft wird vorher - eine leere
     * Nachricht soll als 400 ankommen und nicht erst eine halbe Minute spaeter als
     * gescheiterte Antwort.
     *
     * <p>Dieselbe Kennung noch einmal haengt nichts an. Steht die Nachricht noch ohne
     * Antwort da und laeuft nichts mehr (der Dienst lief neu, oder die Antwort
     * scheiterte), wird nur die Antwort neu angefordert.
     */
    public synchronized ChatView send(String me, String text, String clientId) {
        if (!access.allows(me)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Der Rezept-Chat ist für dich nicht freigeschaltet.");
        }
        if (!agent.isAvailable()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Der Rezept-Chat ist auf diesem Server nicht eingerichtet.");
        }
        String message = text == null ? "" : text.strip();
        if (message.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Die Nachricht ist leer.");
        }
        if (message.length() > MAX_TEXT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Die Nachricht darf höchstens " + MAX_TEXT + " Zeichen haben.");
        }
        String id = cleanClientId(clientId);
        Job running = jobs.get(me);
        boolean busy = running != null && running.isRunning();
        Conversation conversation = repository.conversation(me);
        List<ChatMessage> messages = conversation.messages();

        Optional<ChatMessage> known = id == null ? Optional.empty() : conversation.message(id);
        if (known.isPresent()) {
            boolean unanswered = !messages.isEmpty() && id.equals(messages.getLast().id())
                    && ChatMessage.USER.equals(known.get().role());
            if (busy || !unanswered) {
                return view(me);
            }
            // Neu anfordern: dieselbe Nachricht, das Gespraech davor als Vorgeschichte.
            countUsage(me);
            start(me, conversation.id(), messages.subList(0, messages.size() - 1), known.get().text());
            return view(me);
        }

        if (busy) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Die letzte Antwort kommt noch.");
        }
        countUsage(me);
        Instant now = clock.instant();
        ChatMessage own = new ChatMessage(id == null ? UUID.randomUUID().toString() : id,
                ChatMessage.USER, message, now, null, null);
        Conversation updated = repository.append(me, own, null, now).orElseThrow();
        start(me, updated.id(), messages, message);
        return view(me);
    }

    /** Zaehlt eine Anfrage - oder lehnt sie ab, wenn das Tageslimit erreicht ist. */
    private void countUsage(String me) {
        LocalDate today = LocalDate.now(clock);
        if (repository.usage(me, today) >= dailyLimit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Für heute ist das Limit des Rezept-Chats erreicht.");
        }
        repository.countUsage(me, today);
    }

    private void start(String me, String conversationId, List<ChatMessage> history, String text) {
        Job job = new Job(conversationId, clock.instant());
        jobs.put(me, job);
        List<ChatMessage> before = List.copyOf(history);
        executor.submit(() -> {
            try {
                // Die Namen erst jetzt lesen: bis die Warteschlange hier ankommt, kann
                // ein Rezept dazugekommen sein.
                List<String> names = shopping.board(me).dishes().stream().map(Dish::name).toList();
                RecipeAnswer answer = agent.answer(names, before, text);
                Instant now = clock.instant();
                ChatMessage reply = new ChatMessage(UUID.randomUUID().toString(), ChatMessage.ASSISTANT,
                        answer.reply(), now, answer.draft(), null);
                // Hat die Person inzwischen "Neu" gedrueckt, faellt die Antwort weg.
                repository.append(me, reply, job.conversationId, now);
                job.status = "done";
            } catch (ResponseStatusException e) {
                job.error = e.getReason() == null ? "Die Antwort ist fehlgeschlagen." : e.getReason();
                job.status = JobView.FAILED;
            } catch (RuntimeException e) {
                log.warn("Rezept-Chat: Antwort fuer {} fehlgeschlagen", me, e);
                job.error = "Die Antwort ist fehlgeschlagen.";
                job.status = JobView.FAILED;
            }
        });
    }

    /**
     * "Neu": das Gespraech ist leer. Eine Antwort, die noch unterwegs ist, gehoert zum
     * alten Gespraech und wird verworfen, wenn sie ankommt.
     */
    public synchronized ChatView reset(String me) {
        if (!access.allows(me)) {
            return new ChatView(false, List.of(), null);
        }
        repository.reset(me, clock.instant());
        jobs.remove(me);
        return view(me);
    }

    /**
     * Speichert den Entwurf einer Antwort als Rezept. Ein zweiter Aufruf legt nichts
     * mehr an - der Entwurf weiss, was aus ihm wurde.
     */
    public synchronized Board saveDraft(String me, String messageId) {
        if (!access.allows(me)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Der Rezept-Chat ist für dich nicht freigeschaltet.");
        }
        ChatMessage message = repository.conversation(me).message(messageId)
                .filter(m -> m.draft() != null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Diesen Entwurf gibt es nicht mehr."));
        if (message.savedDishId() == null) {
            // Erst das Rezept, dann der Vermerk: scheitert das Anlegen (der Name ist
            // vergeben), bleibt der Entwurf speicherbar.
            Dish dish = shopping.createRecipe(me, message.draft().toRequest(), "chat");
            repository.markSaved(me, messageId, dish.id());
        }
        return shopping.board(me);
    }

    /**
     * Nimmt nichts Neues mehr an und wartet, bis die Warteschlange leer ist - fuer
     * Tests, die danach ihr Verzeichnis wegraeumen. Im Betrieb endet eine laufende
     * Session mit dem Dienst (systemd beendet die ganze Kontrollgruppe).
     */
    boolean awaitQuiet(long seconds) throws InterruptedException {
        executor.shutdown();
        return executor.awaitTermination(seconds, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Fuer den naechtlichen Lauf: alte Gespraeche und die Zaehler vergangener Tage. */
    public void cleanup() {
        repository.cleanup(clock.instant().minus(IDLE), LocalDate.now(clock).minusDays(7));
    }

    /** Leer heisst keine. Sonst nur eine kleingeschriebene UUID - die App fragt spaeter mit genau ihr. */
    private static String cleanClientId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String id = raw.trim();
        if (!CLIENT_ID.matcher(id).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Die Kennung ist keine UUID.");
        }
        return id;
    }
}
