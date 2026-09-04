package com.fherrmann.shopping.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fehler kommen als Klartext, nicht als JSON-Fehlerseite.
 *
 * <p>Die App zeigt den Rumpf einer Fehlerantwort direkt an ("Ein Eintrag
 * braucht einen Namen."). Eine JSON-Struktur mit Zeitstempel und Pfad
 * drumherum waere dort die schlechtere von beiden Meldungen.
 */
@RestControllerAdvice
class PlainTextErrors {

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<String> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .header("Content-Type", "text/plain;charset=UTF-8")
                .body(e.getReason() == null ? e.getStatusCode().toString() : e.getReason());
    }
}
