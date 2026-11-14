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

import java.sql.SQLException;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;

import org.postgresql.core.BaseConnection;
import org.postgresql.xa.PGXAConnection;
import org.postgresql.xa.PGXADataSource;
import org.springframework.boot.jdbc.XADataSourceWrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.atomikos.spring.AtomikosXADataSourceWrapper;

/**
 * Demo plumbing for {@link Fault#CRASH_AFTER_PREPARE}: hands Atomikos the
 * PostgreSQL {@link XADataSource} as usual, but its connections can halt the
 * JVM right before the phase-2 commit - a point no exception can reach.
 * Not something a real application needs.
 */
@Configuration(proxyBeanMethods = false)
class CrashAfterPrepareConfiguration {

    // the fault of the refund whose transaction this thread commits; the billing
    // and email listeners also commit in two phases, on threads of their own
    private static final ThreadLocal<Fault> REQUESTED = new ThreadLocal<>();

    /** Runs the transaction of a refund, which crashes after prepare if that is its fault. */
    static void armedFor(Fault fault, Runnable transaction) {
        REQUESTED.set(fault);
        try {
            transaction.run();
        } finally {
            REQUESTED.remove();
        }
    }

    @Bean
    XADataSourceWrapper xaDataSourceWrapper() {
        AtomikosXADataSourceWrapper atomikos = new AtomikosXADataSourceWrapper();
        return postgres -> atomikos.wrapDataSource(new CrashingXADataSource((PGXADataSource) postgres));
    }

    private static class CrashingXADataSource extends PGXADataSource {

        // the same database and credentials as the one Spring Boot configured
        CrashingXADataSource(PGXADataSource postgres) {
            setURL(postgres.getURL());
            setUser(postgres.getUser());
            setPassword(postgres.getPassword());
        }

        @Override
        public XAConnection getXAConnection(String user, String password) throws SQLException {
            BaseConnection connection = (BaseConnection) getConnection(user, password);
            return new PGXAConnection(connection) {

                @Override
                public void commit(Xid xid, boolean onePhase) throws XAException {
                    if (!onePhase) {
                        // two-phase commit, after a successful prepare
                        Fault.CRASH_AFTER_PREPARE.triggerIf(REQUESTED.get());
                    }
                    super.commit(xid, onePhase);
                }

            };
        }

    }

}
