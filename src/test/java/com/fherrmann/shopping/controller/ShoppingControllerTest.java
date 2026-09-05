package com.fherrmann.shopping.controller;

import com.fherrmann.shopping.dto.Board;
import com.fherrmann.shopping.dto.ItemRequest;
import com.fherrmann.shopping.model.Item;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ShoppingController.class, SetupController.class})
@Import({SecurityConfig.class, PlainTextErrors.class, TokenRegistry.class})
@TestPropertySource(properties = "shopping.tokens=felix:token-felix-1,freundin:token-freundin-2")
class ShoppingControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @MockitoBean
    private ShoppingService service;

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
        when(service.board("freundin")).thenReturn(board("freundin"));
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
        mockMvc.perform(get("/api/board").cookie(new Cookie("shopping_token", "token-freundin-2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.me").value("freundin"));
    }

    @Test
    void werAnlegtStehtAlsUrheberDa() throws Exception {
        when(service.createItem(eq("freundin"), any())).thenReturn(board("freundin"));
        mockMvc.perform(post("/api/items").header("Authorization", "Bearer token-freundin-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Milch\",\"quantity\":\"2\",\"category\":\"dairy\"}"))
                .andExpect(status().isCreated());
        verify(service).createItem(eq("freundin"), eq(new ItemRequest("Milch", "2", null, "dairy")));
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
}
