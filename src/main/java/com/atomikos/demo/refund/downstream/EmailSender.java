package com.atomikos.demo.refund.downstream;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import com.atomikos.demo.refund.RefundService;

/**
 * The post-commit sender for the irreversible step. A request only arrives here
 * once the refund transaction has committed; a rolled-back refund never sends an email.
 * <p>
 * The "email" is a log line plus a row in sent_email, so the demo can show it.
 * A real SMTP send cannot join the transaction: a crash right after it would send
 * the email again on redelivery, so a real sender should pass the refundId to the
 * mail provider as its idempotency key.
 */
@Component
public class EmailSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmailSender.class);

    private final JdbcTemplate jdbc;

    public EmailSender(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @JmsListener(destination = RefundService.EMAIL_REQUESTS)
    public void onEmailRequested(Map<String, Object> request) {
        jdbc.update("INSERT INTO sent_email (refund_id, customer_id) VALUES (?, ?)",
                request.get("refundId"), request.get("customerId"));
        LOGGER.info("Email: confirmation of refund {} sent to {}",
                request.get("refundId"), request.get("customerId"));
    }
}
