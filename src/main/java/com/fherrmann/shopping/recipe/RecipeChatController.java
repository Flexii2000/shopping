package com.fherrmann.shopping.recipe;

import com.fherrmann.shopping.dto.Board;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * Der Rezept-Chat. Anders als der Rest der API antwortet er nicht mit dem Brett,
 * sondern mit dem Gespraech ({@link ChatView}) - nur das Speichern eines Entwurfs
 * aendert die Rezepte und liefert deshalb wieder das Brett.
 */
@RestController
@RequestMapping("/api/chat")
public class RecipeChatController {

    private final RecipeChatJobs jobs;

    public RecipeChatController(RecipeChatJobs jobs) {
        this.jobs = jobs;
    }

    /** Das Gespraech - und die Nachfrage, solange {@code job.status} "running" ist. */
    @GetMapping
    public ChatView chat(Principal me) {
        return jobs.view(me.getName());
    }

    /** 202: die Nachricht ist angenommen, die Antwort entsteht im Hintergrund. */
    @PostMapping("/messages")
    public ResponseEntity<ChatView> send(Principal me, @RequestBody ChatRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(jobs.send(me.getName(), request == null ? null : request.text(), request == null ? null : request.id()));
    }

    /** "Neu": das Gespraech leeren. */
    @DeleteMapping
    public ChatView reset(Principal me) {
        return jobs.reset(me.getName());
    }

    /** Den Entwurf einer Antwort als Rezept speichern - das Brett mit dem neuen Rezept. */
    @PostMapping("/drafts/{messageId}/save")
    public ResponseEntity<Board> saveDraft(Principal me, @PathVariable String messageId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(jobs.saveDraft(me.getName(), messageId));
    }
}
