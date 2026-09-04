package com.fherrmann.shopping.controller;

import com.fherrmann.shopping.dto.Board;
import com.fherrmann.shopping.dto.DishRequest;
import com.fherrmann.shopping.dto.ItemRequest;
import com.fherrmann.shopping.dto.RecurringRequest;
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
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * Jede Antwort ist das ganze Brett ({@link Board}); die Clients setzen nichts
 * zusammen. {@code me} ist der Name hinter dem Token - er steht als Urheber
 * an allem, was hier angelegt oder abgehakt wird.
 */
@RestController
@RequestMapping("/api")
public class ShoppingController {

    private final ShoppingService service;

    public ShoppingController(ShoppingService service) {
        this.service = service;
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

    @PostMapping("/items/clear-checked")
    public Board clearChecked(Principal me) {
        return service.clearChecked(me.getName());
    }

    // MARK: - Gerichte

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

    /** Das Gericht als Ganzes auf die Liste - je Zutat ein Eintrag. */
    @PostMapping("/dishes/{id}/add")
    public ResponseEntity<Board> addDish(Principal me, @PathVariable String id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addDish(me.getName(), id));
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
