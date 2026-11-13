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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.activemq.ArtemisContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.atomikos.demo.refund.RefundResult.Outcome;

/**
 * Runs the refund against real PostgreSQL and Artemis containers (needs Docker)
 * and checks that all systems agree after each failure of the article's failure table.
 * The CRASH_* faults kill the JVM, so they are exercised by hand: see the README.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = { "refund.saga.sweep-interval=1h", "atomikos.properties.log-base-dir=target/transaction-logs" })
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext // closes the application before the containers stop
class RefundServiceTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withCommand("postgres", "-c", "max_prepared_transactions=100");

    @Container
    static ArtemisContainer artemis = new ArtemisContainer("apache/activemq-artemis:2.44.0-alpine");

    @DynamicPropertySource
    static void connectToContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.artemis.broker-url", artemis::getBrokerUrl);
        registry.add("spring.artemis.user", artemis::getUser);
        registry.add("spring.artemis.password", artemis::getPassword);
    }

    @Autowired
    RefundService refunds;
    @Autowired
    RefundCompensator compensator;
    @Autowired
    Tx tx;
    @Autowired
    JdbcTemplate jdbc;

    String orderId;
    String refundId;

    @BeforeEach
    void newOrder() {
        orderId = "order-" + UUID.randomUUID();
        refundId = "refund-" + UUID.randomUUID();
        tx.run(() -> jdbc.update(
                "INSERT INTO orders (id, customer_id, amount, status) VALUES (?, 'customer-9', 42.00, 'PAID')", orderId));
    }

    @Test
    void aRefundReachesAllFourSystems() {
        RefundResult result = refunds.refund(refundId, orderId, Fault.NONE);

        assertThat(result.outcome()).isEqualTo(Outcome.REFUNDED);
        assertThat(orderStatus()).isEqualTo("REFUNDED");
        assertThat(crmNoteStatus()).isEqualTo("ACTIVE");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(count("billing_ledger")).isEqualTo(1);
            assertThat(count("sent_email")).isEqualTo(1);
        });
    }

    @ParameterizedTest
    @EnumSource(value = Fault.class, names = { "FAIL_AFTER_DB_UPDATE", "FAIL_BEFORE_COMMIT" })
    void aFailureInTheCoreRollsBackDatabaseAndBrokerAndRevertsTheCrm(Fault fault) {
        RefundResult result = refunds.refund(refundId, orderId, fault);

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.detail()).contains(fault.name());
        assertThat(orderStatus()).isEqualTo("PAID");
        assertThat(crmNoteStatus()).isEqualTo("REVERTED");
        assertThat(sagaStatus()).isEqualTo("COMPENSATED");
        assertNoEventAndNoEmail();
    }

    @Test
    void aCrmUpdateWithUnknownOutcomeIsFoundByItsKeyAndReverted() {
        RefundResult result = refunds.refund(refundId, orderId, Fault.CRM_FAILS_AFTER_APPLY);

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(orderStatus()).isEqualTo("PAID");
        assertThat(crmNoteStatus()).isEqualTo("REVERTED");
        assertNoEventAndNoEmail();
    }

    @Test
    void aRejectedCrmUpdateLeavesNothingToRevert() {
        RefundResult result = refunds.refund(refundId, orderId, Fault.CRM_REJECTS);

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(orderStatus()).isEqualTo("PAID");
        assertThat(count("fake_crm_note")).isZero();
        assertThat(sagaStatus()).isEqualTo("COMPENSATED");
    }

    @Test
    void aRetryWithTheSameKeyDoesNotRefundTwice() {
        refunds.refund(refundId, orderId, Fault.NONE);
        RefundResult retry = refunds.refund(refundId, orderId, Fault.NONE);

        assertThat(retry.outcome()).isEqualTo(Outcome.REFUNDED);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(count("billing_ledger")).isEqualTo(1);
            assertThat(count("sent_email")).isEqualTo(1);
        });
    }

    @Test
    void anOrderIsNotRefundedTwiceUnderADifferentKey() {
        refunds.refund(refundId, orderId, Fault.NONE);
        String otherKey = "refund-" + UUID.randomUUID();
        RefundResult second = refunds.refund(otherKey, orderId, Fault.NONE);

        assertThat(second.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(tx.run(() -> jdbc.queryForObject(
                "SELECT status FROM fake_crm_note WHERE idempotency_key = ?", String.class, otherKey)))
                .isEqualTo("REVERTED");
    }

    @Test
    void theSweepCompensatesARefundAbandonedByACrash() {
        // the state a crash after the CRM update leaves behind: a PENDING saga and an active CRM note
        tx.run(() -> {
            jdbc.update("""
                    INSERT INTO refund_saga (refund_id, order_id, customer_id, status, updated_at)
                    VALUES (?, ?, 'customer-9', 'PENDING', now() - interval '10 minutes')""", refundId, orderId);
            return jdbc.update("""
                    INSERT INTO fake_crm_note (idempotency_key, customer_id, note, status)
                    VALUES (?, 'customer-9', 'Refund issued', 'ACTIVE')""", refundId);
        });

        compensator.sweep();

        assertThat(sagaStatus()).isEqualTo("COMPENSATED");
        assertThat(crmNoteStatus()).isEqualTo("REVERTED");
        assertThat(orderStatus()).isEqualTo("PAID");
    }

    private void assertNoEventAndNoEmail() {
        // give a (wrongly) committed message ample time to arrive
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(count("billing_ledger")).isZero();
            assertThat(count("sent_email")).isZero();
        });
    }

    private String orderStatus() {
        return tx.run(() -> jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId));
    }

    private String sagaStatus() {
        return tx.run(() -> jdbc.queryForObject(
                "SELECT status FROM refund_saga WHERE refund_id = ?", String.class, refundId));
    }

    private String crmNoteStatus() {
        return tx.run(() -> jdbc.queryForObject(
                "SELECT status FROM fake_crm_note WHERE idempotency_key = ?", String.class, refundId));
    }

    /** Rows belonging to this test's refund. */
    private int count(String table) {
        String keyColumn = table.equals("fake_crm_note") ? "idempotency_key" : "refund_id";
        return tx.run(() -> jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + keyColumn + " = ?", Integer.class, refundId));
    }
}
