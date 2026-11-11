package com.atomikos.demo.refund;

import java.util.concurrent.Callable;

import org.springframework.stereotype.Component;

import com.atomikos.icatch.jta.template.TransactionTemplate;

/**
 * Runs a block of work in a JTA transaction, using the Atomikos TransactionTemplate.
 * An exception thrown by the work rolls the transaction back.
 */
@Component
public class Tx {

    private static final int TIMEOUT_SECONDS = 10;

    public <T> T run(Callable<T> work) {
        try {
            return new TransactionTemplate().withTimeout(TIMEOUT_SECONDS).required().execute(work);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
