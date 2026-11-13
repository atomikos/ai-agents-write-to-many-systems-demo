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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The failure table of the article, without coordination.
 * <p>
 * DON'T DO THIS: the refund as four separate calls, each committing on its
 * own ({@code POST /naive-refunds}). These tests pass because things go wrong;
 * {@link FailureTableWithHybridPatternTest} runs the same four failures with
 * the hybrid pattern.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("The failure table, without coordination")
class FailureTableWithoutCoordinationTest extends RunningAppTestBase {

    @Test
    @DisplayName("Crash after step 1 → order refunded, but no event: billing never refunds the money")
    void crashAfterStep1() {
        app.naiveRefundAndCrash(order, Fault.CRASH_AFTER_DB_UPDATE);
        app.restart();

        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(orderStatus()).as("order").isEqualTo("REFUNDED");
            assertThat(billingBookings()).as("billing bookings").isZero();
        });
    }

    /** The event went out, its answer did not come back, and the agent tries again. */
    @Test
    @DisplayName("Broker timeout on step 2, agent retries → event published twice: the customer is refunded twice")
    void brokerTimeoutAndRetry() {
        app.naiveRefund(order, Fault.BROKER_ANSWER_LOST);
        app.naiveRefund(order, Fault.NONE); // the agent retries

        await().atMost(Duration.ofSeconds(10))
               .untilAsserted(() -> assertThat(billingBookings()).as("billing bookings").isEqualTo(2));
    }

    @Test
    @DisplayName("CRM call fails at step 3 → database and broker say \"refunded\", CRM says \"open\"")
    void crmCallFails() {
        app.naiveRefund(order, Fault.CRM_REJECTS);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(orderStatus()).as("order").isEqualTo("REFUNDED");
            assertThat(billingBookings()).as("billing bookings").isOne();
            assertThat(activeCrmNotes()).as("CRM notes").isZero();
        });
    }

    @Test
    @DisplayName("Crash after step 4, agent replays the plan → two confirmation emails")
    void agentReplaysThePlan() {
        app.naiveRefundAndCrash(order, Fault.CRASH_AFTER_COMMIT);
        app.restart();
        app.naiveRefund(order, Fault.NONE); // the replay

        await().atMost(Duration.ofSeconds(10))
               .untilAsserted(() -> assertThat(emails()).as("emails").isEqualTo(2));
    }

}
