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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.springframework.util.FileSystemUtils;
import org.testcontainers.activemq.ArtemisContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL and Artemis in containers (needs Docker), the application in its
 * own JVM, and a new paid order of a new customer for every test - so a late
 * message from an earlier test can never be counted in this one.
 * <p>
 * Every test checks all systems together - order, billing, email, CRM -
 * because the point of the demo is that they agree.
 */
abstract class RunningAppTestBase {

    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withCommand("postgres", "-c", "max_prepared_transactions=100");

    static final ArtemisContainer artemis = new ArtemisContainer("apache/activemq-artemis:2.44.0-alpine");

    static RunningApp app;

    @BeforeAll
    static void startApplication(TestInfo testInfo) throws IOException {
        if (!postgres.isRunning()) {
            postgres.start();
            artemis.start();
        }
        // a fresh transaction log per test class; the application's output is kept here too
        Path workDir = Path.of("target", "running-app", testInfo.getTestClass().orElseThrow().getSimpleName());
        FileSystemUtils.deleteRecursively(workDir);
        Files.createDirectories(workDir);
        app = RunningApp.start(postgres, artemis, workDir);
    }

    @AfterAll
    static void stopApplication() {
        app.close();
    }

    String order;
    String customer;

    @BeforeEach
    void newPaidOrder() throws SQLException {
        order = "order-" + UUID.randomUUID();
        customer = "customer-" + UUID.randomUUID();
        try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                PreparedStatement insert = db.prepareStatement(
                        "INSERT INTO orders (id, customer_id, amount, status) VALUES (?, ?, 42.00, 'PAID')")) {
            insert.setString(1, order);
            insert.setString(2, customer);
            insert.executeUpdate();
        }
    }

    static String newRefundId() {
        return "refund-" + UUID.randomUUID();
    }

    /** Refunded everywhere, once - and it stays that way: no late duplicate shows up. */
    void assertRefundedOnce() {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(this::refundedOnce);
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).untilAsserted(this::refundedOnce);
    }

    /** Everywhere as before the refund (a reverted CRM note counts as no note), also a few seconds later. */
    void assertNothingChanged() {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(this::unchanged);
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).untilAsserted(this::unchanged);
    }

    String orderStatus() {
        return (String) rowsWith("orders", "id", order).get(0).get("status");
    }

    int billingBookings() {
        return rowsWith("billingLedger", "order_id", order).size();
    }

    int emails() {
        return rowsWith("sentEmails", "customer_id", customer).size();
    }

    /** CRM notes of the customer that are not reverted. */
    int activeCrmNotes() {
        return (int) rowsWith("crmNotes", "customer_id", customer).stream()
                .filter(note -> "ACTIVE".equals(note.get("status")))
                .count();
    }

    private void refundedOnce() {
        assertThat(orderStatus()).as("order").isEqualTo("REFUNDED");
        assertThat(billingBookings()).as("billing bookings").isOne();
        assertThat(emails()).as("emails").isOne();
        assertThat(activeCrmNotes()).as("CRM notes").isOne();
    }

    private void unchanged() {
        assertThat(orderStatus()).as("order").isEqualTo("PAID");
        assertThat(billingBookings()).as("billing bookings").isZero();
        assertThat(emails()).as("emails").isZero();
        assertThat(activeCrmNotes()).as("CRM notes").isZero();
    }

    private static List<Map<String, Object>> rowsWith(String system, String column, String value) {
        return app.state().get(system).stream()
                .filter(row -> value.equals(row.get(column)))
                .toList();
    }

}
