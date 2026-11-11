package com.atomikos.demo.refund.crm;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.atomikos.demo.refund.Fault;
import com.atomikos.demo.refund.Tx;

/**
 * Stand-in for a SaaS CRM: a plain HTTP API with no "prepare" step.
 * What makes it a usable saga step is that updates are keyed by an idempotency
 * key and can be looked up and reverted by that key.
 * <p>
 * It is called over real HTTP and commits on its own, independently of the caller.
 */
@RestController
@RequestMapping("/fake-crm/refund-notes")
public class FakeCrmController {

    public record NoteRequest(String customerId, String note) {
    }

    private final Tx tx;
    private final JdbcTemplate jdbc;

    public FakeCrmController(Tx tx, JdbcTemplate jdbc) {
        this.tx = tx;
        this.jdbc = jdbc;
    }

    @PutMapping("/{key}")
    public ResponseEntity<Void> record(@PathVariable String key, @RequestBody NoteRequest request,
            @RequestHeader(name = CrmClient.FAULT_HEADER, defaultValue = "NONE") Fault fault) {
        if (fault == Fault.CRM_REJECTS) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        // idempotent: a retry with the same key does not add a second note
        tx.run(() -> jdbc.update("""
                INSERT INTO fake_crm_note (idempotency_key, customer_id, note, status)
                VALUES (?, ?, ?, 'ACTIVE') ON CONFLICT (idempotency_key) DO NOTHING""",
                key, request.customerId(), request.note()));
        if (fault == Fault.CRM_FAILS_AFTER_APPLY) {
            // the update went through, but the caller cannot know that
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build();
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{key}")
    public ResponseEntity<Map<String, Object>> find(@PathVariable String key) {
        return tx.run(() -> jdbc.queryForList(
                "SELECT idempotency_key, customer_id, note, status FROM fake_crm_note WHERE idempotency_key = ?", key))
                .stream().findFirst()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The compensating call. Reverting twice is harmless; an unknown key is a 404. */
    @DeleteMapping("/{key}")
    public ResponseEntity<Void> revert(@PathVariable String key) {
        int found = tx.run(() -> jdbc.update(
                "UPDATE fake_crm_note SET status = 'REVERTED' WHERE idempotency_key = ?", key));
        return found == 1 ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }
}
