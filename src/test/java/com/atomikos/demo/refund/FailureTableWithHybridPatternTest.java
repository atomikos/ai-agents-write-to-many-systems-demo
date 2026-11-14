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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.atomikos.demo.refund.RefundResult.Outcome;

/**
 * The failure table of the article, with the hybrid pattern: the same four
 * failures as {@link FailureTableWithoutCoordinationTest}, and every system
 * ends up agreeing.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("The failure table, with the hybrid pattern")
class FailureTableWithHybridPatternTest extends RunningAppTestBase {

    /** Recovery rolls back the XA transaction; the sweep undoes the CRM update. */
    @Test
    @DisplayName("Crash after step 1 → database and broker roll back together, and the CRM update is undone")
    void crashAfterStep1() {
        app.refundAndCrash(newRefundId(), order, Fault.CRASH_AFTER_DB_UPDATE);
        app.restart();

        assertNothingChanged();
    }

    /**
     * The timeout happens inside the XA transaction, so nothing is left behind.
     * FAILED means nothing was done, so the agent retries as a new refund, with a new refundId.
     */
    @Test
    @DisplayName("Broker timeout on step 2, agent retries → the retry starts clean: refunded once")
    void brokerTimeoutAndRetry() {
        RefundResult first = app.refund(newRefundId(), order, Fault.BROKER_ANSWER_LOST);
        RefundResult retry = app.refund(newRefundId(), order, Fault.NONE);

        assertThat(first.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(retry.outcome()).isEqualTo(Outcome.REFUNDED);
        assertRefundedOnce();
    }

    /** The CRM goes first, so when it refuses there is nothing to undo. */
    @Test
    @DisplayName("CRM call fails at step 3 → nothing changed anywhere")
    void crmCallFails() {
        RefundResult result = app.refund(newRefundId(), order, Fault.CRM_REJECTS);

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertNothingChanged();
    }

    /** The replay carries the same refundId, which reports the outcome of the first call. */
    @Test
    @DisplayName("Crash after step 4, agent replays the plan → one confirmation email")
    void agentReplaysThePlan() {
        String refundId = newRefundId();
        app.refundAndCrash(refundId, order, Fault.CRASH_AFTER_COMMIT);
        app.restart();
        RefundResult replay = app.refund(refundId, order, Fault.NONE);

        assertThat(replay.outcome()).isEqualTo(Outcome.REFUNDED);
        assertRefundedOnce();
    }

}
