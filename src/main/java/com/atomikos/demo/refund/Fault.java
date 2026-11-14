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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Failure injection points for the demo, matching the failure table of the article.
 * FAIL_* throws an exception; CRASH_* kills the JVM without any cleanup.
 */
public enum Fault {
    NONE,
    /** The CRM refuses the update; nothing was applied. */
    CRM_REJECTS,
    /** The CRM applies the update but the caller sees an error: "did it go through?" */
    CRM_FAILS_AFTER_APPLY,
    /** JVM dies after the CRM update, before the XA transaction starts. */
    CRASH_AFTER_CRM,
    /** Exception after the order row is updated, before anything is sent to the broker. */
    FAIL_AFTER_DB_UPDATE,
    /** JVM dies after the order row is updated, before anything is sent to the broker. */
    CRASH_AFTER_DB_UPDATE,
    /** The broker takes the RefundIssued event, but its answer is lost: the caller sees an error. */
    BROKER_ANSWER_LOST,
    /** Exception after all three writes (order, event, email request), before commit. */
    FAIL_BEFORE_COMMIT,
    /** JVM dies after all three writes, before commit. */
    CRASH_BEFORE_COMMIT,
    /**
     * JVM dies in the middle of the commit: PostgreSQL and Artemis are prepared and
     * the commit decision is logged, but PostgreSQL is not committed yet.
     */
    CRASH_AFTER_PREPARE,
    /** JVM dies after commit, before the caller gets an answer. */
    CRASH_AFTER_COMMIT;

    private static final Logger LOGGER = LoggerFactory.getLogger(Fault.class);

    /** Called at each injection point with the point's own fault; acts if it is the requested one. */
    void triggerIf(Fault requested) {
        if (this != requested) {
            return;
        }
        if (name().startsWith("CRASH_")) {
            LOGGER.error("Injected fault {}: halting the JVM", this);
            Runtime.getRuntime().halt(1);
        }
        throw new InjectedFailureException("Injected fault " + this);
    }

    public static class InjectedFailureException extends RuntimeException {
        public InjectedFailureException(String message) {
            super(message);
        }
    }
}
