package com.fherrmann.shopping.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Die Token dieses Dienstes: je Person einer, mit Namen.
 *
 * <p>Absichtlich nicht der Privat-Token von fherrmann.com. Der oeffnet alles
 * Private auf einmal; hier soll eine zweite Person genau die Einkaufsliste
 * bedienen koennen - und sonst nichts. Form in der Umgebung:
 * {@code SHOPPING_TOKENS=felix:…,joana:…}.
 */
@Component
public class TokenRegistry {

    private record Entry(String name, byte[] token) {
    }

    private final List<Entry> entries = new ArrayList<>();

    public TokenRegistry(@Value("${shopping.tokens}") String configured) {
        for (String part : configured.split(",")) {
            String piece = part.trim();
            if (piece.isEmpty()) {
                continue;
            }
            int colon = piece.indexOf(':');
            if (colon <= 0 || colon == piece.length() - 1) {
                throw new IllegalStateException("shopping.tokens: erwartet name:token, bekommen: " + piece);
            }
            String name = piece.substring(0, colon).trim();
            String token = piece.substring(colon + 1).trim();
            if (name.isEmpty() || token.length() < 8) {
                throw new IllegalStateException("shopping.tokens: Name fehlt oder Token zu kurz bei " + name);
            }
            if (nameFor(token).isPresent()) {
                throw new IllegalStateException("shopping.tokens: der Token von " + name + " ist schon vergeben.");
            }
            entries.add(new Entry(name, token.getBytes(StandardCharsets.UTF_8)));
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("shopping.tokens ist leer - ohne Token kommt niemand hinein.");
        }
    }

    /** Der Name zum Token - in konstanter Zeit ueber alle Eintraege, ohne fruehes Ende. */
    public Optional<String> nameFor(String supplied) {
        if (supplied == null) {
            return Optional.empty();
        }
        byte[] candidate = supplied.getBytes(StandardCharsets.UTF_8);
        String found = null;
        for (Entry entry : entries) {
            if (MessageDigest.isEqual(candidate, entry.token()) && found == null) {
                found = entry.name();
            }
        }
        return Optional.ofNullable(found);
    }

    public List<String> names() {
        return entries.stream().map(Entry::name).toList();
    }
}
