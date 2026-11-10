package com.fherrmann.shopping.healthy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Der Kalorienzaehler, nachgestellt: der interne Endpunkt mit Dienst-Token, eine Antwort je Person. */
class HttpHealthyTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TOKEN = "dienst-token-fuer-den-test";
    private static final HealthyDish DISH = new HealthyDish("Rezept: Pasta mit Feta", 152.38, 5.71, 18.57, 5.71, 420);

    private HttpServer server;
    private String base;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String reply = """
            [{"person":"felix","status":"created","message":null},
             {"person":"joana","status":"rejected","message":"kcal is too high"},
             {"person":"gast","status":"not-linked"}]""";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        paths.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
        if (!("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            reply(ex, 403, "{}");
            return;
        }
        bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        reply(ex, status, reply);
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private HttpHealthy healthy(String url, String token) {
        return new HttpHealthy(url, token, JSON, HttpClient.newHttpClient());
    }

    @Test
    void einAufrufFuerAlleMitDemDienstToken() {
        List<Healthy.Outcome> outcomes = healthy(base + "/", TOKEN).writeDish(List.of("felix", "joana", "gast"), DISH);

        assertThat(paths).containsExactly("POST /api/internal/recipe-dishes");
        JsonNode body = JSON.readTree(bodies.getFirst());
        assertThat(body.path("persons").size()).isEqualTo(3);
        assertThat(body.path("persons").get(1).asString()).isEqualTo("joana");
        JsonNode dish = body.path("dish");
        assertThat(dish.propertyNames()).containsExactly("name", "kcal", "proteinG", "carbsG", "fatG", "portionG");
        assertThat(dish.path("name").asString()).isEqualTo("Rezept: Pasta mit Feta");
        assertThat(dish.path("kcal").asDouble()).isEqualTo(152.38);
        assertThat(dish.path("portionG").asDouble()).isEqualTo(420.0);

        assertThat(outcomes).containsExactly(
                new Healthy.Outcome("felix", "created", null),
                new Healthy.Outcome("joana", "rejected", "kcal is too high"),
                new Healthy.Outcome("gast", "not-linked", null));
    }

    /** Der Zusammenhang fuer den Chat: genau eine Person, mit dem Dienst-Token, kompakt zurueck. */
    @Test
    void derZusammenhangWirdFuerGenauEinePersonGeholt() {
        reply = "{ \"targets\": {\"kcal\": 1800, \"proteinG\": 110},\n \"vegan\": false }";

        assertThat(healthy(base, TOKEN).context("joana"))
                .contains("{\"targets\":{\"kcal\":1800,\"proteinG\":110},\"vegan\":false}");
        assertThat(paths).containsExactly("POST /api/internal/recipe-context");
        assertThat(JSON.readTree(bodies.getFirst()).propertyNames()).containsExactly("person");
        assertThat(JSON.readTree(bodies.getFirst()).path("person").asString()).isEqualTo("joana");
    }

    /** 404 heisst: kein Healthy - kein Fehler. Alles andere ohne Objekt ist einer. */
    @Test
    void ohneHealthyGibtEsKeinenZusammenhang() {
        status = 404;
        assertThat(healthy(base, TOKEN).context("gast")).isEmpty();
        assertThat(healthy(base, "").context("felix")).isEmpty();
        status = 200;
        reply = "[1,2]";
        assertThatThrownBy(() -> healthy(base, TOKEN).context("felix"))
                .isInstanceOf(HealthyException.class).hasMessage("Kalorienzähler antwortet ohne Objekt.");
        reply = "{\"x\":\"" + "a".repeat(HttpHealthy.MAX_CONTEXT) + "\"}";
        assertThatThrownBy(() -> healthy(base, TOKEN).context("felix"))
                .isInstanceOf(HealthyException.class).hasMessageStartingWith("Zusammenhang zu groß");
        status = 500;
        assertThatThrownBy(() -> healthy(base, TOKEN).context("felix"))
                .isInstanceOf(HealthyException.class).hasMessage("Kalorienzähler antwortet mit 500.");
    }

    @Test
    void ohneTokenIstDerWegNichtEingerichtet() {
        assertThat(healthy(base, "").isConfigured()).isFalse();
        assertThat(healthy(base, "  ").isConfigured()).isFalse();
        assertThat(healthy(base, null).isConfigured()).isFalse();
        assertThat(healthy(base, TOKEN).isConfigured()).isTrue();
    }

    /** Abgelehnter Token, alter Stand ohne den Endpunkt, Fehler im Kalorienzaehler: niemand hat das Gericht. */
    @Test
    void jedeAndereAntwortIstEinAusfall() {
        assertThatThrownBy(() -> healthy(base, "falscher-token").writeDish(List.of("felix"), DISH))
                .isInstanceOf(HealthyException.class).hasMessage("Kalorienzähler antwortet mit 403.");
        status = 404;
        assertThatThrownBy(() -> healthy(base, TOKEN).writeDish(List.of("felix"), DISH))
                .isInstanceOf(HealthyException.class).hasMessage("Kalorienzähler antwortet mit 404.");
        status = 500;
        assertThatThrownBy(() -> healthy(base, TOKEN).writeDish(List.of("felix"), DISH))
                .isInstanceOf(HealthyException.class).hasMessage("Kalorienzähler antwortet mit 500.");
        status = 200;
        reply = "<html>nginx</html>";
        assertThatThrownBy(() -> healthy(base, TOKEN).writeDish(List.of("felix"), DISH))
                .isInstanceOf(HealthyException.class).hasMessage("Kalorienzähler antwortet ohne JSON.");
        reply = "{\"status\":\"ok\"}";
        assertThatThrownBy(() -> healthy(base, TOKEN).writeDish(List.of("felix"), DISH))
                .isInstanceOf(HealthyException.class).hasMessage("Kalorienzähler antwortet ohne Liste.");
    }

    /** Nicht erreichbar - und in keiner Meldung steht der Token. */
    @Test
    void nichtErreichbarIstEinAusfallOhneDenTokenInDerMeldung() throws IOException {
        // Ein Port, an dem gerade niemand mehr lauscht.
        int closed;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        assertThatThrownBy(() -> healthy("http://127.0.0.1:" + closed, TOKEN).writeDish(List.of("felix"), DISH))
                .isInstanceOf(HealthyException.class)
                .hasMessage("Kalorienzähler nicht erreichbar.")
                .satisfies(e -> assertThat(String.valueOf(e) + e.getCause()).doesNotContain(TOKEN));
    }
}
