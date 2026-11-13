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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Demo of the hybrid pattern "XA where you can, sagas where you must":
 * a customer refund exposed to an AI agent as ONE tool ({@code POST /refunds}).
 * <p>
 * The Atomikos starter auto-configures the JTA transaction manager and enlists
 * the XA DataSource (PostgreSQL) and XA ConnectionFactory (Artemis) that
 * Spring Boot creates from {@code application.properties}.
 */
@SpringBootApplication
@EnableScheduling
public class RefundApplication {

    public static void main(String[] args) {
        SpringApplication.run(RefundApplication.class, args);
    }
}
