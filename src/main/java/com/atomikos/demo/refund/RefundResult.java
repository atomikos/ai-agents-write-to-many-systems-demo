package com.atomikos.demo.refund;

/**
 * What the agent gets back: it worked, it didn't, or an earlier call with the same key is still running.
 */
public record RefundResult(String refundId, String orderId, Outcome outcome, String detail) {

    public enum Outcome {
        /** Everything is done (or will be: the email is sent from the queue). */
        REFUNDED,
        /** Nothing was done; whatever was started has been undone or is being undone. */
        FAILED,
        /** Another call with the same refundId has not finished yet; ask again later. */
        IN_PROGRESS
    }
}
