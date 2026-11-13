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

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Durable saga state. Every status change is a guarded UPDATE ("... WHERE status = ..."),
 * so the request thread and the recovery sweep can never both win.
 * <p>
 * All methods must be called inside a JTA transaction (see {@link Tx}).
 */
@Repository
public class RefundSagaRepository {

    public enum Status { PENDING, COMPLETED, COMPENSATING, COMPENSATED }

    public record Saga(String refundId, String orderId, String customerId, Status status, String detail) {
    }

    private final JdbcTemplate jdbc;

    public RefundSagaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false if a saga with this refundId already exists */
    public boolean start(String refundId, String orderId, String customerId) {
        return jdbc.update("""
                INSERT INTO refund_saga (refund_id, order_id, customer_id, status)
                VALUES (?, ?, ?, 'PENDING') ON CONFLICT (refund_id) DO NOTHING""",
                refundId, orderId, customerId) == 1;
    }

    public Optional<Saga> find(String refundId) {
        return jdbc.query("SELECT refund_id, order_id, customer_id, status, detail FROM refund_saga WHERE refund_id = ?",
                (rs, i) -> new Saga(rs.getString(1), rs.getString(2), rs.getString(3),
                        Status.valueOf(rs.getString(4)), rs.getString(5)),
                refundId).stream().findFirst();
    }

    /** Part of the XA transaction: only succeeds if nobody started compensating. */
    public boolean complete(String refundId) {
        return jdbc.update("""
                UPDATE refund_saga SET status = 'COMPLETED', updated_at = now()
                WHERE refund_id = ? AND status = 'PENDING'""", refundId) == 1;
    }

    /**
     * Claims the saga for compensation. If the XA transaction of this saga is still
     * in doubt, this UPDATE waits on its row lock - and then fails if it committed.
     */
    public boolean claimForCompensation(String refundId, String reason) {
        return jdbc.update("""
                UPDATE refund_saga SET status = 'COMPENSATING', detail = COALESCE(detail, ?), updated_at = now()
                WHERE refund_id = ? AND status IN ('PENDING', 'COMPENSATING')""",
                truncate(reason), refundId) == 1;
    }

    public void compensated(String refundId) {
        jdbc.update("""
                UPDATE refund_saga SET status = 'COMPENSATED', updated_at = now()
                WHERE refund_id = ? AND status = 'COMPENSATING'""", refundId);
    }

    /** Sagas left behind by a crash or by a failed compensation. */
    public List<String> findNeedingCompensation(Duration staleAfter) {
        return jdbc.queryForList("""
                SELECT refund_id FROM refund_saga
                WHERE status = 'COMPENSATING'
                   OR (status = 'PENDING' AND updated_at < now() - make_interval(secs => ?))
                ORDER BY created_at""",
                String.class, staleAfter.toMillis() / 1000.0);
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 512 ? s : s.substring(0, 512);
    }
}
