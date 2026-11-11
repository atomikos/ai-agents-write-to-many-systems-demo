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
    /** Exception after all three writes (order, event, email request), before commit. */
    FAIL_BEFORE_COMMIT,
    /** JVM dies after all three writes, before commit. */
    CRASH_BEFORE_COMMIT,
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
