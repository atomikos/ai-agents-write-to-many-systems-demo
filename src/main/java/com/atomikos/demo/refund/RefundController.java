package com.atomikos.demo.refund;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.atomikos.demo.refund.RefundService.OrderNotFoundException;

/**
 * The single tool the agent calls. It answers "it worked" or "it didn't";
 * the agent never has to reason about partial outcomes.
 */
@RestController
public class RefundController {

    /** @param fault optional failure injection, for the demo only */
    public record RefundRequest(String refundId, String orderId, Fault fault) {
    }

    /** @param fault optional failure injection, for the demo only */
    public record NaiveRefundRequest(String orderId, Fault fault) {
    }

    private final RefundService refunds;
    private final NaiveRefundService naiveRefunds;
    private final Tx tx;
    private final JdbcTemplate jdbc;

    public RefundController(RefundService refunds, NaiveRefundService naiveRefunds, Tx tx, JdbcTemplate jdbc) {
        this.refunds = refunds;
        this.naiveRefunds = naiveRefunds;
        this.tx = tx;
        this.jdbc = jdbc;
    }

    @PostMapping("/refunds")
    public ResponseEntity<RefundResult> refund(@RequestBody RefundRequest request) {
        if (isBlank(request.refundId()) || isBlank(request.orderId())) {
            throw new IllegalArgumentException("refundId and orderId are required");
        }
        Fault fault = request.fault() == null ? Fault.NONE : request.fault();
        RefundResult result = refunds.refund(request.refundId(), request.orderId(), fault);
        HttpStatus status = switch (result.outcome()) {
            case REFUNDED -> HttpStatus.OK;
            case IN_PROGRESS -> HttpStatus.ACCEPTED;
            case FAILED -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(result);
    }

    /** For comparison only: the same refund without coordination (see {@link NaiveRefundService}). */
    @PostMapping("/naive-refunds")
    public void naiveRefund(@RequestBody NaiveRefundRequest request) {
        if (isBlank(request.orderId())) {
            throw new IllegalArgumentException("orderId is required");
        }
        Fault fault = Fault.NONE;
        if (request.fault() != null) {
            fault = request.fault();
        }
        naiveRefunds.refund(request.orderId(), fault);
    }

    /** Everything every system knows, to check consistency after a demo scenario. */
    @GetMapping("/state")
    public Map<String, Object> state() {
        return tx.run(() -> {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("orders", jdbc.queryForList("SELECT id, customer_id, amount, status FROM orders ORDER BY id"));
            state.put("sagas", jdbc.queryForList(
                    "SELECT refund_id, order_id, status, detail FROM refund_saga ORDER BY created_at"));
            state.put("billingLedger", jdbc.queryForList("SELECT refund_id, order_id, amount FROM billing_ledger ORDER BY refund_id"));
            state.put("sentEmails", jdbc.queryForList("SELECT refund_id, customer_id FROM sent_email ORDER BY refund_id"));
            state.put("crmNotes", jdbc.queryForList(
                    "SELECT idempotency_key, customer_id, note, status FROM fake_crm_note ORDER BY idempotency_key"));
            state.put("inDoubt", jdbc.queryForList("SELECT gid, prepared FROM pg_prepared_xacts"));
            return state;
        });
    }

    /** Demo plumbing: all orders back to PAID, everything else emptied. */
    @PostMapping("/reset")
    public void reset() {
        tx.run(() -> {
            jdbc.update("DELETE FROM refund_saga");
            jdbc.update("DELETE FROM billing_ledger");
            jdbc.update("DELETE FROM sent_email");
            jdbc.update("DELETE FROM fake_crm_note");
            return jdbc.update("UPDATE orders SET status = 'PAID'");
        });
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(OrderNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
