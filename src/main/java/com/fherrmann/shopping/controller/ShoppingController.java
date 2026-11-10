package com.fherrmann.shopping.controller;

import com.fherrmann.shopping.dto.Board;
import com.fherrmann.shopping.dto.ClearCheckedRequest;
import com.fherrmann.shopping.dto.CookResult;
import com.fherrmann.shopping.dto.DishAddRequest;
import com.fherrmann.shopping.dto.DishRequest;
import com.fherrmann.shopping.dto.ItemRequest;
import com.fherrmann.shopping.dto.RecurringRequest;
import com.fherrmann.shopping.dto.ScaledDish;
import com.fherrmann.shopping.healthy.CookService;
import com.fherrmann.shopping.service.ShoppingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * Jede Antwort, die etwas aendert, ist das ganze Brett ({@link Board}); die Clients
 * setzen nichts zusammen. {@code me} ist der Name hinter dem Token - er steht als
 * Urheber an allem, was hier angelegt oder abgehakt wird.
 *
 * <p>Zwei Ausnahmen, die nichts an Liste und Rezepten aendern: die umgerechneten
 * Zutaten ({@link ScaledDish}) und das Ergebnis von "Kochen" ({@link CookResult}).
 * Der Rezept-Chat hat seinen eigenen Controller.
 */
@RestController
@RequestMapping("/api")
public class ShoppingController {

    private final ShoppingService service;
    private final CookService cookService;

    public ShoppingController(ShoppingService service, CookService cookService) {
        this.service = service;
        this.cookService = cookService;
    }

    @GetMapping("/board")
    public Board board(Principal me) {
        return service.board(me.getName());
    }

    // MARK: - Eintraege

    @PostMapping("/items")
    public ResponseEntity<Board> createItem(Principal me, @RequestBody ItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createItem(me.getName(), request));
    }

    @PutMapping("/items/{id}")
    public Board updateItem(Principal me, @PathVariable String id, @RequestBody ItemRequest request) {
        return service.updateItem(me.getName(), id, request);
    }

    @DeleteMapping("/items/{id}")
    public Board deleteItem(Principal me, @PathVariable String id) {
        return service.deleteItem(me.getName(), id);
    }

    @PostMapping("/items/{id}/check")
    public Board check(Principal me, @PathVariable String id) {
        return service.check(me.getName(), id);
    }

    @DeleteMapping("/items/{id}/check")
    public Board uncheck(Principal me, @PathVariable String id) {
        return service.uncheck(me.getName(), id);
    }

    /** Ohne Rumpf alle abgehakten (Weboberflaeche), mit {@code {ids}} nur diese. */
    @PostMapping("/items/clear-checked")
    public Board clearChecked(Principal me, @RequestBody(required = false) ClearCheckedRequest request) {
        return service.clearChecked(me.getName(), request);
    }

    // MARK: - Rezepte (im Pfad weiter "dishes" - aeltere Apps rufen genau das)

    @PostMapping("/dishes")
    public ResponseEntity<Board> createDish(Principal me, @RequestBody DishRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createDish(me.getName(), request));
    }

    @PutMapping("/dishes/{id}")
    public Board updateDish(Principal me, @PathVariable String id, @RequestBody DishRequest request) {
        return service.updateDish(me.getName(), id, request);
    }

    @DeleteMapping("/dishes/{id}")
    public Board deleteDish(Principal me, @PathVariable String id) {
        return service.deleteDish(me.getName(), id);
    }

    /**
     * Das Rezept als Ganzes auf die Liste - je Zutat ein Eintrag. Mit {@code portions}
     * umgerechnet, mit {@code skip} ohne die genannten Zutaten.
     */
    @PostMapping("/dishes/{id}/add")
    public ResponseEntity<Board> addDish(Principal me, @PathVariable String id,
                                         @RequestBody(required = false) DishAddRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addDish(me.getName(), id, request));
    }

    /** Die Zutaten fuer eine andere Portionenzahl - ohne {@code portions} die des Rezepts. */
    @GetMapping("/dishes/{id}/scaled")
    public ScaledDish scaled(@PathVariable String id, @RequestParam(required = false) Integer portions) {
        return service.scaled(id, portions);
    }

    /**
     * "Kochen": legt das Rezept im Kalorienzaehler als Gericht an, bei allen mit
     * Healthy-Zugang. Immer 200 - die Antwort sagt je Person, was daraus wurde. Ein
     * Rumpf wird nicht gebraucht und nicht gelesen.
     */
    @PostMapping("/dishes/{id}/cook")
    public CookResult cook(Principal me, @PathVariable String id) {
        return cookService.cook(me.getName(), id);
    }

    // MARK: - Regeln

    @PostMapping("/recurring")
    public ResponseEntity<Board> createRule(Principal me, @RequestBody RecurringRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createRule(me.getName(), request));
    }

    @PutMapping("/recurring/{id}")
    public Board updateRule(Principal me, @PathVariable String id, @RequestBody RecurringRequest request) {
        return service.updateRule(me.getName(), id, request);
    }

    @DeleteMapping("/recurring/{id}")
    public Board deleteRule(Principal me, @PathVariable String id) {
        return service.deleteRule(me.getName(), id);
    }
}
