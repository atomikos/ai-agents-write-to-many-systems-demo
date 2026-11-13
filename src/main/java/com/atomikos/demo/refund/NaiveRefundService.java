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
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

import com.atomikos.demo.refund.RefundService.OrderNotFoundException;
import com.atomikos.demo.refund.crm.CrmClient;

/**
 * DON'T DO THIS: the same refund as four separate steps, each committing on its
 * own - what an agent gets when it chains four tools. It is only here to make the
 * failure table of the article happen; compare {@link RefundService}.
 * <p>
 * Every call is a new refund with an id of its own, so a retry or a replay refunds again.
 */
@Service
public class NaiveRefundService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NaiveRefundService.class);

    private record Order(String customerId, BigDecimal amount) {
    }

    private final Tx tx;
    private final JdbcTemplate jdbc;
    private final JmsTemplate jms;
    private final CrmClient crm;

    public NaiveRefundService(Tx tx, JdbcTemplate jdbc, JmsTemplate jms, CrmClient crm) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.jms = jms;
        this.crm = crm;
    }

    public void refund(String orderId, Fault fault) {
        String refundId = "naive-" + UUID.randomUUID();
        Order order = tx.run(() -> findOrder(orderId))
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        String customerId = order.customerId();

        // 1. mark the order as refunded - without the "AND status = 'PAID'" guard, because
        //    with it a retry after a lost broker answer would never reach the CRM or the email
        tx.run(() -> jdbc.update("UPDATE orders SET status = 'REFUNDED' WHERE id = ?", orderId));
        Fault.CRASH_AFTER_DB_UPDATE.triggerIf(fault);

        // 2. publish RefundIssued for billing
        tx.run(() -> {
            jms.convertAndSend(RefundService.REFUND_EVENTS, Map.of(
                    "refundId", refundId,
                    "orderId", orderId,
                    "customerId", customerId,
                    "amount", order.amount().toPlainString()));
            return null;
        });
        Fault.BROKER_ANSWER_LOST.triggerIf(fault);

        // 3. update the CRM
        crm.recordRefund(refundId, customerId,
                "Refund of " + order.amount() + " issued for " + orderId, fault);

        // 4. send the confirmation email
        tx.run(() -> jdbc.update("INSERT INTO sent_email (refund_id, customer_id) VALUES (?, ?)",
                refundId, customerId));
        LOGGER.info("Email: confirmation of refund {} sent to {}", refundId, customerId);

        Fault.CRASH_AFTER_COMMIT.triggerIf(fault);
    }

    private Optional<Order> findOrder(String orderId) {
        return jdbc.query("SELECT customer_id, amount FROM orders WHERE id = ?",
                (rs, i) -> new Order(rs.getString(1), rs.getBigDecimal(2)),
                orderId).stream().findFirst();
    }
}
