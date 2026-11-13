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
 * Real crashes of the hybrid refund, at each point where it matters, and what
 * the restart makes of them.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Crash recovery")
class CrashRecoveryTest extends RunningAppTestBase {

    @Test
    @DisplayName("A crash between the CRM update and the transaction → the minimal coordinator undoes the CRM update")
    void crashAfterTheCrmUpdate() {
        String refundId = newRefundId();
        app.refundAndCrash(refundId, order, Fault.CRASH_AFTER_CRM);
        app.restart();

        assertNothingChanged();
        assertThat(app.refund(refundId, order, Fault.NONE).outcome()).as("asking again").isEqualTo(Outcome.FAILED);
    }

    @Test
    @DisplayName("A crash after all three writes, before the commit → they roll back together, and the CRM update is undone")
    void crashBeforeTheCommit() {
        app.refundAndCrash(newRefundId(), order, Fault.CRASH_BEFORE_COMMIT);
        app.restart();

        assertNothingChanged();
    }

    /**
     * The JVM halts when PostgreSQL gets its phase-2 commit: both resources are
     * prepared and the commit decision is logged, but nothing is committed yet.
     */
    @Test
    @DisplayName("\"A JVM crash in the middle is resolved by recovery on restart\" → the commit is completed")
    void crashInTheMiddleOfTheCommit() {
        String refundId = newRefundId();
        app.refundAndCrash(refundId, order, Fault.CRASH_AFTER_PREPARE);
        app.restart();

        assertRefundedOnce();
        assertThat(app.refund(refundId, order, Fault.NONE).outcome()).as("asking again").isEqualTo(Outcome.REFUNDED);
        assertThat(app.state().get("inDoubt")).as("transactions in doubt in PostgreSQL").isEmpty();
    }

}
