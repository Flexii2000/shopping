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

    private final URI endpoint;
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
        this.endpoint = URI.create(baseUrl.strip().replaceAll("/+$", "") + "/api/internal/recipe-dishes");
        this.token = token == null ? "" : token.strip();
        this.objectMapper = objectMapper;
        this.http = http;
    }

    @Override
    public boolean isConfigured() {
        return !token.isEmpty();
    }

    @Override
    public List<Outcome> writeDish(List<String> persons, HealthyDish dish) {
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

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Nur die Art des Fehlers, nie die Anfrage: in ihr steckt der Token.
            throw new HealthyException("Kalorienzähler nicht erreichbar.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HealthyException("Abgebrochen.", e);
        }
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
}
