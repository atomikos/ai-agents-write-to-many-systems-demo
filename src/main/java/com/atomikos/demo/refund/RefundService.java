/*
 * Copyright 2026 Atomikos
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.atomikos.demo.refund;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

import com.atomikos.demo.refund.RefundResult.Outcome;
import com.atomikos.demo.refund.RefundSagaRepository.Saga;
import com.atomikos.demo.refund.crm.CrmClient;

/**
 * The refund as one business action:
 * <ol>
 * <li>record the intent durably (saga row, keyed by the caller's refundId),</li>
 * <li>update the CRM - the only step that cannot join a transaction, so it gets
 *     an idempotency key and a compensation,</li>
 * <li>the transactional core in ONE XA transaction: order row, RefundIssued event,
 *     "send email" request, and the saga row itself.</li>
 * </ol>
 * The email is never sent from here: it is a message that only exists once the
 * transaction has committed.
 */
@Service
public class RefundService {

    public static final String REFUND_EVENTS = "refund.events";
    public static final String EMAIL_REQUESTS = "email.requests";

    private static final Logger LOGGER = LoggerFactory.getLogger(RefundService.class);

    private record Order(String id, String customerId, BigDecimal amount) {
    }

    private final Tx tx;
    private final JdbcTemplate jdbc;
    private final JmsTemplate jms;
    private final RefundSagaRepository sagas;
    private final CrmClient crm;
    private final RefundCompensator compensator;

    public RefundService(Tx tx, JdbcTemplate jdbc, JmsTemplate jms, RefundSagaRepository sagas,
            CrmClient crm, RefundCompensator compensator) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.jms = jms;
        this.sagas = sagas;
        this.crm = crm;
        this.compensator = compensator;
    }

    /**
     * @param refundId idempotency key chosen by the caller: calling again with the
     *                 same key never refunds twice, it reports the outcome of the first call
     */
    public RefundResult refund(String refundId, String orderId, Fault fault) {
        Order order = tx.run(() -> findOrder(orderId))
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        boolean started = tx.run(() -> sagas.start(refundId, order.id(), order.customerId()));
        if (!started) {
            return outcomeOfEarlierCall(refundId);
        }

        try {
            crm.recordRefund(refundId, order.customerId(),
                    "Refund of " + order.amount() + " issued for " + order.id(), fault);
            Fault.CRASH_AFTER_CRM.triggerIf(fault);
            commitTransactionalCore(refundId, order, fault);
        } catch (Exception e) {
            LOGGER.warn("Refund {} failed, compensating: {}", refundId, e.toString());
            return compensate(refundId, e);
        }

        Fault.CRASH_AFTER_COMMIT.triggerIf(fault);
        return new RefundResult(refundId, order.id(), Outcome.REFUNDED, null);
    }

    /** Database and broker writes commit together or not at all, including across a crash. */
    private void commitTransactionalCore(String refundId, Order order, Fault fault) {
        tx.run(() -> {
            if (!sagas.complete(refundId)) {
                throw new IllegalStateException("Refund " + refundId + " is already being compensated");
            }
            // 1. mark the order as refunded; the guard prevents refunding an order twice
            int updated = jdbc.update("UPDATE orders SET status = 'REFUNDED' WHERE id = ? AND status = 'PAID'",
                    order.id());
            if (updated != 1) {
                throw new IllegalStateException("Order " + order.id() + " is not refundable (already refunded?)");
            }
            Fault.FAIL_AFTER_DB_UPDATE.triggerIf(fault);

            Map<String, Object> message = Map.of(
                    "refundId", refundId,
                    "orderId", order.id(),
                    "customerId", order.customerId(),
                    "amount", order.amount().toPlainString());
            // 2. publish RefundIssued for billing and analytics
            jms.convertAndSend(REFUND_EVENTS, message);
            // 3. queue the confirmation email for the post-commit sender
            jms.convertAndSend(EMAIL_REQUESTS, message);

            Fault.FAIL_BEFORE_COMMIT.triggerIf(fault);
            Fault.CRASH_BEFORE_COMMIT.triggerIf(fault);
            return null;
        });
    }

    private RefundResult compensate(String refundId, Exception cause) {
        try {
            compensator.compensate(refundId, cause.getMessage());
        } catch (Exception e) {
            // the saga row stays behind as COMPENSATING: the sweep retries until it succeeds
            LOGGER.warn("Compensation of refund {} failed, will be retried: {}", refundId, e.toString());
        }
        // the saga row is the truth, e.g. if the commit succeeded although we saw an error
        return outcomeOfEarlierCall(refundId);
    }

    private RefundResult outcomeOfEarlierCall(String refundId) {
        Saga saga = tx.run(() -> sagas.find(refundId)).orElseThrow();
        Outcome outcome = switch (saga.status()) {
            case COMPLETED -> Outcome.REFUNDED;
            case PENDING -> Outcome.IN_PROGRESS;
            case COMPENSATING, COMPENSATED -> Outcome.FAILED;
        };
        return new RefundResult(refundId, saga.orderId(), outcome, saga.detail());
    }

    private Optional<Order> findOrder(String orderId) {
        return jdbc.query("SELECT id, customer_id, amount FROM orders WHERE id = ?",
                (rs, i) -> new Order(rs.getString(1), rs.getString(2), rs.getBigDecimal(3)),
                orderId).stream().findFirst();
    }

    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(String orderId) {
            super("Unknown order: " + orderId);
        }
    }
}
