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
