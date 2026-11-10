package com.fherrmann.shopping;

import com.fherrmann.shopping.model.ShoppingData;
import com.fherrmann.shopping.recipe.ChatView;
import com.fherrmann.shopping.recipe.RecipeChatJobs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@TestPropertySource(properties = {
        "shopping.data-file=build/tmp/context-test/shopping.json",
        "shopping.chat-file=build/tmp/context-test/recipe-chat.json"})
class ShoppingApplicationTests {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RecipeChatJobs chat;

    @Test
    void contextLoads() {
    }

    /**
     * Mit dem Mapper, den der Dienst wirklich benutzt: die Datei von vor den Rezepten
     * muss sich lesen lassen. Der Test im Repository nimmt einen eigenen Mapper - hier
     * zaehlt, was Spring konfiguriert hat.
     */
    @Test
    void derMapperDesDienstesLiestDieDateiVonVorDenRezepten() throws Exception {
        ShoppingData data;
        try (InputStream in = getClass().getResourceAsStream("/shopping-before-recipes.json")) {
            data = objectMapper.readValue(in.readAllBytes(), ShoppingData.class);
        }
        assertEquals(6, data.items().size());
        assertEquals(2, data.dishes().size());
        assertNull(data.dishes().get(0).portions());
        assertFalse(data.dishes().get(0).ingredients().get(0).pantry());
        assertEquals(data, objectMapper.readValue(objectMapper.writeValueAsString(data), ShoppingData.class));
    }

    /** Ohne Kommando und ohne Freigabe laedt alles, und der Chat ist schlicht nicht da. */
    @Test
    void ohneEinrichtungGibtEsKeinenChatAberAllesAndere() {
        assertEquals(new ChatView(false, java.util.List.of(), null), chat.view("felix"));
    }
}
