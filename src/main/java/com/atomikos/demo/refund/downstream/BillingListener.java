package com.atomikos.demo.refund.downstream;

import java.math.BigDecimal;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import com.atomikos.demo.refund.RefundService;

/**
 * Stand-in for billing: consumes RefundIssued and books the refund.
 * Receiving the message and writing the ledger row are one XA transaction,
 * so the consumer needs no duplicate detection of its own.
 */
@Component
public class BillingListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(BillingListener.class);

    private final JdbcTemplate jdbc;

    public BillingListener(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @JmsListener(destination = RefundService.REFUND_EVENTS)
    public void onRefundIssued(Map<String, Object> event) {
        jdbc.update("INSERT INTO billing_ledger (refund_id, order_id, amount) VALUES (?, ?, ?)",
                event.get("refundId"), event.get("orderId"), new BigDecimal((String) event.get("amount")));
        LOGGER.info("Billing: booked refund {} of {} for {}",
                event.get("refundId"), event.get("amount"), event.get("orderId"));
    }
}
