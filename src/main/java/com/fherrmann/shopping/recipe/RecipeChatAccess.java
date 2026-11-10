package com.fherrmann.shopping.recipe;

import com.fherrmann.shopping.security.TokenRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Wer den Rezept-Chat benutzen darf.
 *
 * <p>Jede Antwort ist eine Claude-Code-Session unter Felix' Login und zaehlt auf sein
 * Kontingent - deshalb ist der Chat je Person schaltbar statt einfach fuer jeden mit
 * Token an. Form in der Umgebung: {@code SHOPPING_RECIPE_CHAT=felix,joana}; {@code *}
 * heisst alle. <b>Leer heisst niemand</b>: ein Token, das spaeter dazukommt, bekommt
 * den Chat nicht von selbst.
 */
@Component
public class RecipeChatAccess {

    private static final Logger log = LoggerFactory.getLogger(RecipeChatAccess.class);

    private final boolean everyone;
    private final Set<String> people = new LinkedHashSet<>();

    public RecipeChatAccess(TokenRegistry registry, @Value("${shopping.recipe-agent.people:}") String configured) {
        String value = configured == null ? "" : configured.trim();
        this.everyone = value.equals("*");
        if (!everyone) {
            for (String part : value.split(",")) {
                String name = part.trim();
                if (name.isEmpty()) {
                    continue;
                }
                // Ein Tippfehler hier soll nicht die ganze Einkaufsliste am Start hindern.
                if (!registry.names().contains(name)) {
                    log.warn("shopping.recipe-agent.people: '{}' hat keinen Token - ignoriert.", name);
                    continue;
                }
                people.add(name);
            }
        }
    }

    public boolean allows(String user) {
        return everyone || people.contains(user);
    }
}
