package com.fherrmann.shopping.controller;

import com.fherrmann.shopping.dto.Board;
import com.fherrmann.shopping.dto.ClearCheckedRequest;
import com.fherrmann.shopping.dto.CookResult;
import com.fherrmann.shopping.dto.DishAddRequest;
import com.fherrmann.shopping.dto.DishRequest;
import com.fherrmann.shopping.dto.ItemRequest;
import com.fherrmann.shopping.dto.ScaledDish;
import com.fherrmann.shopping.healthy.CookService;
import com.fherrmann.shopping.model.Dish;
import com.fherrmann.shopping.model.Ingredient;
import com.fherrmann.shopping.model.Item;
import com.fherrmann.shopping.model.Nutrition;
import com.fherrmann.shopping.recipe.ChatMessage;
import com.fherrmann.shopping.recipe.ChatView;
import com.fherrmann.shopping.recipe.RecipeChatController;
import com.fherrmann.shopping.recipe.RecipeChatJobs;
import com.fherrmann.shopping.recipe.RecipeDraft;
import com.fherrmann.shopping.security.SecurityConfig;
import com.fherrmann.shopping.security.TokenRegistry;
import com.fherrmann.shopping.service.ShoppingService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ShoppingController.class, SetupController.class, RecipeChatController.class})
@Import({SecurityConfig.class, PlainTextErrors.class, TokenRegistry.class})
@TestPropertySource(properties = "shopping.tokens=felix:token-felix-1,joana:token-joana-2")
class ShoppingControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @MockitoBean
    private ShoppingService service;

    @MockitoBean
    private CookService cookService;

    @MockitoBean
    private RecipeChatJobs chat;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    private static Board board(String me) {
        Instant now = Instant.parse("2026-09-03T12:00:00Z");
        return new Board(me, List.of(new Item("i1", "Milch", "2", null, "dairy", now, "felix", null, null, null, null)),
                List.of(), List.of());
    }

    @Test
    void ohneTokenKeinZugang() throws Exception {
        mockMvc.perform(get("/api/board"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Kein Zugang - bitte den Link mit Token öffnen."));
        mockMvc.perform(get("/api/board").header("Authorization", "Bearer falsch"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/board").cookie(new Cookie("shopping_token", "falsch")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bearerUndCookieGehenUndDerNameKommtMit() throws Exception {
        when(service.board("felix")).thenReturn(board("felix"));
        when(service.board("joana")).thenReturn(board("joana"));
        mockMvc.perform(get("/api/board").header("Authorization", "Bearer token-felix-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.me").value("felix"))
                .andExpect(jsonPath("$.items[0].name").value("Milch"))
                .andExpect(jsonPath("$.items[0].addedAt").value("2026-09-03T12:00:00Z"))
                .andExpect(jsonPath("$.items[0].category").value("dairy"))
                .andExpect(jsonPath("$.categories[0].key").value("produce"))
                .andExpect(jsonPath("$.categories[0].label").value("Obst & Gemüse"))
                .andExpect(jsonPath("$.categories[0].emoji").value("🥦"))
                .andExpect(jsonPath("$.categories[0].symbol").value("carrot"))
                .andExpect(jsonPath("$.categories[0].color").value("#34A853"))
                .andExpect(jsonPath("$.categories[13].key").value("other"));
        mockMvc.perform(get("/api/board").cookie(new Cookie("shopping_token", "token-joana-2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.me").value("joana"));
    }

    @Test
    void werAnlegtStehtAlsUrheberDa() throws Exception {
        when(service.createItem(eq("joana"), any())).thenReturn(board("joana"));
        mockMvc.perform(post("/api/items").header("Authorization", "Bearer token-joana-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Milch\",\"quantity\":\"2\",\"category\":\"dairy\"}"))
                .andExpect(status().isCreated());
        verify(service).createItem(eq("joana"), eq(new ItemRequest("Milch", "2", null, "dairy")));
    }

    @Test
    void fehlerKommenAlsKlartext() throws Exception {
        when(service.createItem(any(), any())).thenThrow(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ein Eintrag braucht einen Namen."));
        mockMvc.perform(post("/api/items").header("Authorization", "Bearer token-felix-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Ein Eintrag braucht einen Namen."));
    }

    @Test
    void setupSetztDasCookieUndLeitetUm() throws Exception {
        mockMvc.perform(get("/setup").param("token", "token-felix-1"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/"))
                .andExpect(cookie().value("shopping_token", "token-felix-1"))
                .andExpect(cookie().httpOnly("shopping_token", true))
                .andExpect(cookie().secure("shopping_token", true))
                .andExpect(cookie().maxAge("shopping_token", 400 * 24 * 3600));
        mockMvc.perform(get("/setup").param("token", "falsch")).andExpect(status().isForbidden());
        mockMvc.perform(get("/setup")).andExpect(status().isForbidden());
    }

    @Test
    void dieSeiteSelbstIstFrei() throws Exception {
        // Ohne Token muss man den Link mit Token ueberhaupt oeffnen koennen;
        // die Seite zeigt dann "Kein Zugang", die API bleibt zu.
        mockMvc.perform(get("/index.html").accept(MediaType.TEXT_HTML)).andExpect(status().isOk());
    }

    /** Ohne Rumpf wie bisher (Weboberflaeche, aeltere Apps), mit Rumpf die Liste bzw. die Kennungen der App. */
    @Test
    void abgehakteEntfernenUndGerichtMitUndOhneRumpf() throws Exception {
        when(service.clearChecked(eq("joana"), any())).thenReturn(board("joana"));
        when(service.addDish(eq("joana"), eq("d1"), any())).thenReturn(board("joana"));
        mockMvc.perform(post("/api/items/clear-checked").header("Authorization", "Bearer token-joana-2"))
                .andExpect(status().isOk());
        verify(service).clearChecked("joana", null);
        mockMvc.perform(post("/api/items/clear-checked").header("Authorization", "Bearer token-joana-2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[\"i1\"]}"))
                .andExpect(status().isOk());
        verify(service).clearChecked("joana", new ClearCheckedRequest(List.of("i1")));

        mockMvc.perform(post("/api/dishes/d1/add").header("Authorization", "Bearer token-joana-2"))
                .andExpect(status().isCreated());
        verify(service).addDish("joana", "d1", null);
        mockMvc.perform(post("/api/dishes/d1/add").header("Authorization", "Bearer token-joana-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"name\":\"Lauch\",\"id\":\"x\"}]}"))
                .andExpect(status().isCreated());
        verify(service).addDish("joana", "d1", new DishAddRequest(List.of(new DishAddRequest.Entry("Lauch", "x"))));
    }

    // MARK: - Rezepte

    private static final String JOANA = "Bearer token-joana-2";

    /** Der Rumpf aelterer Apps kommt als "nicht angegeben" an, der neue mit allem - so entscheidet der Dienst, was bleibt. */
    @Test
    void rezeptAnlegenUndAendernMitAltemUndNeuemRumpf() throws Exception {
        when(service.updateDish(eq("joana"), eq("d1"), any())).thenReturn(board("joana"));
        when(service.createDish(eq("joana"), any())).thenReturn(board("joana"));
        mockMvc.perform(put("/api/dishes/d1").header("Authorization", JOANA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Suppe\",\"ingredients\":[{\"name\":\"Lauch\",\"quantity\":\"1 Stange\"}]}"))
                .andExpect(status().isOk());
        verify(service).updateDish("joana", "d1", new DishRequest("Suppe",
                List.of(new DishRequest.IngredientRequest("Lauch", "1 Stange"))));

        mockMvc.perform(post("/api/dishes").header("Authorization", JOANA).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Pasta mit Feta","portions":2,"minutes":25,
                                 "ingredients":[{"name":"Penne","quantity":"250 g","pantry":false},
                                                {"name":"Olivenöl","quantity":"2 EL","pantry":true}],
                                 "steps":["Wasser aufsetzen."],
                                 "nutrition":{"kcal":640,"proteinG":24,"carbsG":78,"fatG":24},
                                 "gramsPerPortion":420}"""))
                .andExpect(status().isCreated());
        verify(service).createDish("joana", new DishRequest("Pasta mit Feta",
                List.of(new DishRequest.IngredientRequest("Penne", "250 g", false),
                        new DishRequest.IngredientRequest("Olivenöl", "2 EL", true)),
                2, 25, List.of("Wasser aufsetzen."),
                new DishRequest.NutritionRequest(640.0, 24.0, 78.0, 24.0), 420.0));

        // "Naehrwerte loeschen" ist ein leeres Objekt, nicht ein fehlendes.
        mockMvc.perform(put("/api/dishes/d1").header("Authorization", JOANA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Suppe\",\"ingredients\":[],\"steps\":[],\"portions\":0,\"nutrition\":{}}"))
                .andExpect(status().isOk());
        verify(service).updateDish("joana", "d1", new DishRequest("Suppe", List.of(), 0, null, List.of(),
                new DishRequest.NutritionRequest(null, null, null, null), null));
    }

    /** So sieht ein Rezept im Brett aus - die Felder, die die Apps lesen. */
    @Test
    void einRezeptImBrett() throws Exception {
        Instant now = Instant.parse("2026-11-10T12:00:00Z");
        Dish recipe = new Dish("d1", "Pasta mit Feta",
                List.of(new Ingredient("Penne", "250 g", false), new Ingredient("Olivenöl", "2 EL", true)), now,
                2, 25, List.of("Wasser aufsetzen."), new Nutrition(640.0, 24.0, 78.0, 24.0), 420.0, "chat", "joana");
        Dish old = new Dish("d2", "Suppe", List.of(new Ingredient("Lauch", null)), now);
        when(service.board("joana")).thenReturn(new Board("joana", List.of(), List.of(recipe, old), List.of()));

        mockMvc.perform(get("/api/board").header("Authorization", JOANA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dishes[0].name").value("Pasta mit Feta"))
                .andExpect(jsonPath("$.dishes[0].portions").value(2))
                .andExpect(jsonPath("$.dishes[0].minutes").value(25))
                .andExpect(jsonPath("$.dishes[0].ingredients[1].pantry").value(true))
                .andExpect(jsonPath("$.dishes[0].steps[0]").value("Wasser aufsetzen."))
                .andExpect(jsonPath("$.dishes[0].nutrition.kcal").value(640.0))
                .andExpect(jsonPath("$.dishes[0].gramsPerPortion").value(420.0))
                .andExpect(jsonPath("$.dishes[0].source").value("chat"))
                .andExpect(jsonPath("$.dishes[0].createdBy").value("joana"))
                // Ein Gericht von frueher: dieselben Schluessel, nur leer.
                .andExpect(jsonPath("$.dishes[1].portions").doesNotExist())
                .andExpect(jsonPath("$.dishes[1].steps").isEmpty())
                .andExpect(jsonPath("$.dishes[1].nutrition").doesNotExist())
                .andExpect(jsonPath("$.dishes[1].ingredients[0].pantry").value(false));
    }

    @Test
    void aufDieListeMitPortionenUndOhneVorrat() throws Exception {
        when(service.addDish(eq("joana"), eq("d1"), any())).thenReturn(board("joana"));
        mockMvc.perform(post("/api/dishes/d1/add").header("Authorization", JOANA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"portions\":4,\"skip\":[\"Olivenöl\"],\"items\":[{\"name\":\"Penne\",\"id\":\"x\"}]}"))
                .andExpect(status().isCreated());
        verify(service).addDish("joana", "d1", new DishAddRequest(
                List.of(new DishAddRequest.Entry("Penne", "x")), 4, List.of("Olivenöl")));
    }

    @Test
    void umgerechneteZutaten() throws Exception {
        when(service.scaled("d1", 3)).thenReturn(new ScaledDish("d1", 3, 2, List.of(
                new ScaledDish.ScaledIngredient("Penne", "375 g", false, true),
                new ScaledDish.ScaledIngredient("Basilikum", "eine Handvoll", false, false))));
        mockMvc.perform(get("/api/dishes/d1/scaled").param("portions", "3").header("Authorization", JOANA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dishId").value("d1"))
                .andExpect(jsonPath("$.portions").value(3))
                .andExpect(jsonPath("$.basePortions").value(2))
                .andExpect(jsonPath("$.ingredients[0].quantity").value("375 g"))
                .andExpect(jsonPath("$.ingredients[0].scaled").value(true))
                .andExpect(jsonPath("$.ingredients[1].scaled").value(false));
        mockMvc.perform(get("/api/dishes/d1/scaled").header("Authorization", JOANA)).andExpect(status().isOk());
        verify(service).scaled("d1", null);
        mockMvc.perform(get("/api/dishes/d1/scaled")).andExpect(status().isUnauthorized());
    }

    /** Kochen antwortet immer mit 200 - je Person steht dabei, was im Kalorienzaehler daraus wurde. */
    @Test
    void kochenSagtJePersonWasDarausWurde() throws Exception {
        when(cookService.cook("joana", "d1")).thenReturn(new CookResult("d1", null, List.of(
                new CookResult.PersonResult("felix", "created", null),
                new CookResult.PersonResult("joana", "failed", "Kalorienzähler nicht erreichbar."))));
        when(cookService.cook("joana", "d2")).thenReturn(new CookResult("d2", CookResult.NO_NUTRITION, List.of()));
        mockMvc.perform(post("/api/dishes/d1/cook").header("Authorization", JOANA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dishId").value("d1"))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.healthy[0].person").value("felix"))
                .andExpect(jsonPath("$.healthy[0].status").value("created"))
                .andExpect(jsonPath("$.healthy[1].message").value("Kalorienzähler nicht erreichbar."));
        // Ein Rumpf stoert nicht.
        mockMvc.perform(post("/api/dishes/d2/cook").header("Authorization", JOANA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"portions\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("no-nutrition"))
                .andExpect(jsonPath("$.healthy").isEmpty());
        mockMvc.perform(post("/api/dishes/d1/cook")).andExpect(status().isUnauthorized());
    }

    // MARK: - Rezept-Chat

    private static ChatView chatView(ChatView.JobView job) {
        Instant now = Instant.parse("2026-11-10T17:02:11Z");
        RecipeDraft draft = new RecipeDraft("Pasta mit Feta", 2, 25,
                List.of(new Ingredient("Penne", "250 g", false)), List.of("Kochen."),
                new Nutrition(640.0, 24.0, 78.0, 24.0), 420.0);
        return new ChatView(true, List.of(
                new ChatMessage("m1", ChatMessage.USER, "Etwas mit Pasta und Feta", now, null, null),
                new ChatMessage("m2", ChatMessage.ASSISTANT, "Wie wäre es damit?", now, draft, null)), job);
    }

    @Test
    void derChatAlsGespraechMitEntwurf() throws Exception {
        when(chat.view("joana")).thenReturn(chatView(null));
        mockMvc.perform(get("/api/chat").header("Authorization", JOANA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.job").doesNotExist())
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].at").value("2026-11-10T17:02:11Z"))
                .andExpect(jsonPath("$.messages[0].draft").doesNotExist())
                .andExpect(jsonPath("$.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.messages[1].draft.name").value("Pasta mit Feta"))
                .andExpect(jsonPath("$.messages[1].draft.portions").value(2))
                .andExpect(jsonPath("$.messages[1].draft.ingredients[0].quantity").value("250 g"))
                .andExpect(jsonPath("$.messages[1].draft.nutrition.proteinG").value(24.0))
                .andExpect(jsonPath("$.messages[1].draft.gramsPerPortion").value(420.0))
                .andExpect(jsonPath("$.messages[1].savedDishId").doesNotExist());
        mockMvc.perform(get("/api/chat")).andExpect(status().isUnauthorized());
    }

    @Test
    void eineNachrichtWirdAngenommenUndImHintergrundBeantwortet() throws Exception {
        when(chat.send("joana", "Etwas mit Pasta", "1a2b3c4d-0000-4000-8000-00000000000a"))
                .thenReturn(chatView(new ChatView.JobView(ChatView.JobView.RUNNING, 0L, null)));
        mockMvc.perform(post("/api/chat/messages").header("Authorization", JOANA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Etwas mit Pasta\",\"id\":\"1a2b3c4d-0000-4000-8000-00000000000a\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.job.status").value("running"))
                .andExpect(jsonPath("$.job.elapsedSeconds").value(0))
                .andExpect(jsonPath("$.job.error").doesNotExist());

        when(chat.send(eq("joana"), any(), any())).thenThrow(
                new ResponseStatusException(HttpStatus.CONFLICT, "Die letzte Antwort kommt noch."));
        mockMvc.perform(post("/api/chat/messages").header("Authorization", JOANA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"und jetzt vegetarisch\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().string("Die letzte Antwort kommt noch."));
    }

    @Test
    void neuLeertUndSpeichernLiefertDasBrett() throws Exception {
        when(chat.reset("joana")).thenReturn(new ChatView(true, List.of(), null));
        when(chat.saveDraft("joana", "m2")).thenReturn(board("joana"));
        mockMvc.perform(delete("/api/chat").header("Authorization", JOANA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isEmpty());
        mockMvc.perform(post("/api/chat/drafts/m2/save").header("Authorization", JOANA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.me").value("joana"));
        mockMvc.perform(post("/api/chat/drafts/m2/save")).andExpect(status().isUnauthorized());
        verifyNoInteractions(cookService);
    }
}
