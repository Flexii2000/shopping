package com.fherrmann.shopping.recipe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Liest und schreibt {@code recipe-chat.json}: je Person ein Gespraech mit dem
 * Rezept-Agenten und wie oft sie heute schon gefragt hat.
 *
 * <p><b>Eine eigene Datei, nicht {@code shopping.json}.</b> Ein kaputter Verlauf darf
 * die Einkaufsliste nicht mitreissen, und der Arbeitsthread des Chats soll nie auf
 * die Sperre der Liste warten. Geschrieben wird erst daneben, dann umbenannt; die
 * Datei gehoert nur dem Dienst ({@code rw-------} - so legt
 * {@code Files.createTempFile} sie an).
 */
@Repository
public class RecipeChatRepository {

    private static final Logger log = LoggerFactory.getLogger(RecipeChatRepository.class);

    /** So viele Nachrichten bleiben je Gespraech - aeltere fallen vorn heraus. */
    static final int MAX_MESSAGES = 30;

    /**
     * Ein Gespraech. Die Kennung wechselt mit "Neu": eine Antwort, die noch fuer das
     * alte Gespraech unterwegs war, erkennt daran, dass sie nicht mehr gefragt ist.
     */
    public record Conversation(String id, List<ChatMessage> messages, Instant updatedAt) {

        public Conversation {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }

        static Conversation empty() {
            return new Conversation(null, List.of(), null);
        }

        public Optional<ChatMessage> message(String id) {
            return messages.stream().filter(m -> m.id() != null && m.id().equals(id)).findFirst();
        }
    }

    /** Die Form der Datei. */
    record Stored(Map<String, Conversation> conversations, Map<String, Map<String, Integer>> usage) {

        Stored {
            conversations = conversations == null ? new LinkedHashMap<>() : new LinkedHashMap<>(conversations);
            Map<String, Map<String, Integer>> copy = new LinkedHashMap<>();
            if (usage != null) {
                usage.forEach((person, days) -> copy.put(person, days == null ? new LinkedHashMap<>() : new LinkedHashMap<>(days)));
            }
            usage = copy;
        }
    }

    private final Path file;
    private final ObjectMapper objectMapper;

    public RecipeChatRepository(
            @Value("${shopping.chat-file:data/recipe-chat.json}") String file,
            ObjectMapper objectMapper) {
        this.file = Path.of(file);
        this.objectMapper = objectMapper;
    }

    /** Das Gespraech der Person; ohne eines ein leeres ohne Kennung. */
    public synchronized Conversation conversation(String person) {
        return load().conversations().getOrDefault(person, Conversation.empty());
    }

    /**
     * Haengt eine Nachricht an.
     *
     * @param expectedId die Kennung des Gespraechs, zu dem die Nachricht gehoert, oder
     *                   {@code null} fuer "das aktuelle". Passt sie nicht mehr - die
     *                   Person hat inzwischen "Neu" gedrueckt -, faellt die Nachricht weg.
     * @return das Gespraech danach, oder leer, wenn die Nachricht nicht mehr gefragt war
     */
    public synchronized Optional<Conversation> append(String person, ChatMessage message, String expectedId, Instant now) {
        Stored stored = load();
        Conversation current = stored.conversations().get(person);
        if (expectedId != null && (current == null || !expectedId.equals(current.id()))) {
            return Optional.empty();
        }
        String id = current == null || current.id() == null ? newId() : current.id();
        List<ChatMessage> messages = new ArrayList<>(current == null ? List.of() : current.messages());
        messages.add(message);
        while (messages.size() > MAX_MESSAGES) {
            messages.removeFirst();
        }
        Conversation updated = new Conversation(id, messages, now);
        stored.conversations().put(person, updated);
        save(stored);
        return Optional.of(updated);
    }

    /** "Neu": ein leeres Gespraech mit neuer Kennung. */
    public synchronized Conversation reset(String person, Instant now) {
        Stored stored = load();
        Conversation fresh = new Conversation(newId(), List.of(), now);
        stored.conversations().put(person, fresh);
        save(stored);
        return fresh;
    }

    /** Vermerkt am Entwurf, welches Rezept aus ihm wurde. */
    public synchronized void markSaved(String person, String messageId, String dishId) {
        Stored stored = load();
        Conversation current = stored.conversations().get(person);
        if (current == null) {
            return;
        }
        List<ChatMessage> messages = current.messages().stream()
                .map(m -> m.id() != null && m.id().equals(messageId) ? m.withSavedDishId(dishId) : m).toList();
        stored.conversations().put(person, new Conversation(current.id(), messages, current.updatedAt()));
        save(stored);
    }

    public synchronized int usage(String person, LocalDate day) {
        return load().usage().getOrDefault(person, Map.of()).getOrDefault(day.toString(), 0);
    }

    public synchronized void countUsage(String person, LocalDate day) {
        Stored stored = load();
        stored.usage().computeIfAbsent(person, p -> new LinkedHashMap<>()).merge(day.toString(), 1, Integer::sum);
        save(stored);
    }

    /**
     * Der Aufraeumer: Gespraeche, in denen so lange nichts mehr passiert ist, und
     * Zaehler vergangener Tage.
     */
    public synchronized void cleanup(Instant idleBefore, LocalDate keepUsageFrom) {
        if (!Files.exists(file)) {
            return;
        }
        Stored stored = load();
        boolean changed = stored.conversations().entrySet()
                .removeIf(e -> e.getValue().updatedAt() == null || e.getValue().updatedAt().isBefore(idleBefore));
        String keepFrom = keepUsageFrom.toString();
        for (Map<String, Integer> days : stored.usage().values()) {
            // yyyy-MM-dd sortiert sich als Text wie als Datum.
            changed |= days.keySet().removeIf(day -> day.compareTo(keepFrom) < 0);
        }
        changed |= stored.usage().values().removeIf(Map::isEmpty);
        if (changed) {
            save(stored);
        }
    }

    private Stored load() {
        if (!Files.exists(file)) {
            return new Stored(null, null);
        }
        try {
            return objectMapper.readValue(Files.readAllBytes(file), Stored.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read recipe chat: " + file, e);
        } catch (RuntimeException e) {
            // Ein Verlauf, der sich nicht lesen laesst, soll den Chat nicht fuer immer
            // sperren: leer weiter, das naechste Schreiben ersetzt die Datei. Es ist ein
            // Gespraech, kein Bestand - die Rezepte liegen in shopping.json.
            log.warn("recipe-chat.json ist nicht lesbar - der Chat faengt leer an: {}", e.getMessage());
            return new Stored(null, null);
        }
    }

    private void save(Stored stored) {
        Path temp = null;
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            // createTempFile legt mit rw------- an - der Verlauf gehoert nur dem Dienst.
            temp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), stored);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            temp = null;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write recipe chat: " + file, e);
        } finally {
            // Jackson 3 wirft ungeprueft - auch dann keine halbe Temp-Datei liegen lassen.
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Das Schreiben ist ohnehin gescheitert; der Fehler oben zaehlt.
                }
            }
        }
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
