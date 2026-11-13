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

package com.atomikos.demo.refund.crm;

import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.atomikos.demo.refund.Fault;

/**
 * Client of the CRM: a remote HTTP API that cannot join a transaction.
 * Every update carries an idempotency key that we generate, and the
 * compensation finds the update by that same key.
 */
@Component
public class CrmClient {

    static final String FAULT_HEADER = "X-Demo-Fault";

    private final RestClient http;
    private final String configuredBaseUrl;
    private final Environment environment;

    public CrmClient(@Value("${crm.base-url:}") String configuredBaseUrl, Environment environment) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().requestFactory(requestFactory).build();
        this.configuredBaseUrl = configuredBaseUrl;
        this.environment = environment;
    }

    /** Adds the refund note to the customer's record. Throws if the CRM does not confirm it. */
    public void recordRefund(String idempotencyKey, String customerId, String note, Fault fault) {
        http.put()
                .uri(baseUrl() + "/fake-crm/refund-notes/{key}", idempotencyKey)
                .header(FAULT_HEADER, fault.name())
                .body(Map.of("customerId", customerId, "note", note))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Compensation: reverts the note made with this key.
     *
     * @return false if the CRM has no update with this key (nothing to undo)
     */
    public boolean revertRefund(String idempotencyKey) {
        try {
            http.delete()
                    .uri(baseUrl() + "/fake-crm/refund-notes/{key}", idempotencyKey)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return false;
            }
            throw e;
        }
    }

    private String baseUrl() {
        if (!configuredBaseUrl.isBlank()) {
            return configuredBaseUrl;
        }
        // the fake CRM runs inside this application; its port is only known once the server is up
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        return "http://localhost:" + port;
    }
}
