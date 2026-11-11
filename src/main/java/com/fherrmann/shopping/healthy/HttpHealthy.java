package com.fherrmann.shopping.healthy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Der Kalorienzaehler ueber seinen internen Endpunkt
 * {@code POST /api/internal/recipe-dishes} ({@code 127.0.0.1:48180}, nicht ueber nginx).
 *
 * <p><b>Ein eigener Dienst-Token, keine persoenlichen.</b> Die Einkaufsliste haengt
 * ohne Privat-Gate am Netz. Laegen hier die Healthy-Token von Felix und Joana,
 * oeffnete ein Fehler in diesem Dienst ihnen Kalorienzaehler, Weight Tracker und
 * coHabit. Dieser Token kann genau eines: ein Gericht anlegen. Wer es bekommt,
 * entscheidet der Kalorienzaehler ueber die Namen.
 *
 * <p>Ein Aufruf fuer alle Personen - der Kalorienzaehler liest, vergleicht und
 * schreibt selbst, und ein vorhandenes Gericht behaelt dort Kennung und "vegan".
 */
@Component
public class HttpHealthy implements Healthy {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Mehr ist kein Zusammenhang fuer einen Chat - der Auftrag der Session bleibt klein. */
    static final int MAX_CONTEXT = 16_000;

    private final URI endpoint;
    private final URI contextEndpoint;
    private final String token;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    @Autowired
    public HttpHealthy(@Value("${shopping.healthy.url:http://127.0.0.1:48180}") String baseUrl,
                       @Value("${shopping.healthy.token:}") String token,
                       ObjectMapper objectMapper) {
        this(baseUrl, token, objectMapper, HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    HttpHealthy(String baseUrl, String token, ObjectMapper objectMapper, HttpClient http) {
        String root = baseUrl.strip().replaceAll("/+$", "");
        this.endpoint = URI.create(root + "/api/internal/recipe-dishes");
        this.contextEndpoint = URI.create(root + "/api/internal/recipe-context");
        this.token = token == null ? "" : token.strip();
        this.objectMapper = objectMapper;
        this.http = http;
    }

    @Override
    public boolean isConfigured() {
        return !token.isEmpty();
    }

    @Override
    public List<Outcome> writeDish(List<String> persons, HealthyDish dish, String previousName) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", dish.name());
        values.put("kcal", dish.kcal());
        values.put("proteinG", dish.proteinG());
        values.put("carbsG", dish.carbsG());
        values.put("fatG", dish.fatG());
        values.put("portionG", dish.portionG());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("persons", persons);
        body.put("dish", values);
        if (previousName != null && !previousName.isBlank()) {
            body.put("previousName", previousName);
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() / 100 != 2) {
            throw new HealthyException("Kalorienzähler antwortet mit " + response.statusCode() + ".");
        }
        JsonNode list;
        try {
            list = objectMapper.readTree(response.body());
        } catch (JacksonException e) {
            throw new HealthyException("Kalorienzähler antwortet ohne JSON.", e);
        }
        if (list == null || !list.isArray()) {
            throw new HealthyException("Kalorienzähler antwortet ohne Liste.");
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (JsonNode entry : list) {
            String person = entry.path("person").asString("");
            String status = entry.path("status").asString("");
            if (person.isEmpty() || status.isEmpty()) {
                continue;
            }
            String message = entry.path("message").isString() ? entry.path("message").asString() : null;
            outcomes.add(new Outcome(person, status, message == null || message.isBlank() ? null : message));
        }
        return outcomes;
    }

    /**
     * Fragt genau eine Person ab - die, die im Chat schreibt. Die Antwort geht unveraendert
     * (nur kompakt neu geschrieben) in deren Auftrag; 404 heisst, sie hat kein Healthy.
     */
    @Override
    public Optional<String> context(String person) {
        if (!isConfigured() || person == null || person.isBlank()) {
            return Optional.empty();
        }
        HttpRequest request = HttpRequest.newBuilder(contextEndpoint)
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(Map.of("person", person)), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() / 100 != 2) {
            throw new HealthyException("Kalorienzähler antwortet mit " + response.statusCode() + ".");
        }
        JsonNode context;
        try {
            context = objectMapper.readTree(response.body());
        } catch (JacksonException e) {
            throw new HealthyException("Kalorienzähler antwortet ohne JSON.", e);
        }
        if (context == null || !context.isObject()) {
            throw new HealthyException("Kalorienzähler antwortet ohne Objekt.");
        }
        String compact = objectMapper.writeValueAsString(context);
        if (compact.length() > MAX_CONTEXT) {
            throw new HealthyException("Zusammenhang zu groß (" + compact.length() + " Zeichen).");
        }
        return Optional.of(compact);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Nur die Art des Fehlers, nie die Anfrage: in ihr steckt der Token.
            throw new HealthyException("Kalorienzähler nicht erreichbar.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HealthyException("Abgebrochen.", e);
        }
    }
}
