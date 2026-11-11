package com.atomikos.demo.refund;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.atomikos.demo.refund.crm.CrmClient;

/**
 * The deterministic part that undoes the CRM step: same failure, same undo action,
 * driven by durable state and retried until it succeeds. Deliberately not the model's job.
 * <p>
 * This is a minimal stand-in for a real saga coordinator (Temporal, DBOS, LRA, ...).
 */
@Component
public class RefundCompensator {

    private static final Logger LOGGER = LoggerFactory.getLogger(RefundCompensator.class);

    private final Tx tx;
    private final RefundSagaRepository sagas;
    private final CrmClient crm;
    private final Duration staleAfter;

    public RefundCompensator(Tx tx, RefundSagaRepository sagas, CrmClient crm,
            @Value("${refund.saga.stale-after}") Duration staleAfter) {
        this.tx = tx;
        this.sagas = sagas;
        this.crm = crm;
        this.staleAfter = staleAfter;
    }

    /**
     * Undoes the CRM update of this refund, unless the refund completed after all.
     * Safe to call any number of times, also for a CRM update that never happened.
     */
    public void compensate(String refundId, String reason) {
        boolean claimed = tx.run(() -> sagas.claimForCompensation(refundId, reason));
        if (!claimed) {
            return; // completed or already compensated: nothing to undo
        }
        // finds the update by the key it was made with - or learns that there is none
        boolean found = crm.revertRefund(refundId);
        tx.run(() -> {
            sagas.compensated(refundId);
            return null;
        });
        LOGGER.info("Refund {} compensated ({})", refundId,
                found ? "CRM update reverted" : "no CRM update to revert");
    }

    /** Picks up sagas left behind by a crash, and compensations that failed earlier. */
    @Scheduled(fixedDelayString = "${refund.saga.sweep-interval}")
    public void sweep() {
        List<String> refundIds = tx.run(() -> sagas.findNeedingCompensation(staleAfter));
        for (String refundId : refundIds) {
            try {
                LOGGER.info("Found unfinished refund {}, compensating", refundId);
                compensate(refundId, "Abandoned (crash or timeout) and compensated by the sweep");
            } catch (Exception e) {
                LOGGER.warn("Compensation of refund {} failed, will be retried: {}", refundId, e.toString());
            }
        }
    }
}
