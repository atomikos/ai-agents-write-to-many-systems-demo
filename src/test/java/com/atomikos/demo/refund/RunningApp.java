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
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.web.client.RestClient;
import org.testcontainers.activemq.ArtemisContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The demo application in its own JVM, called over HTTP as an agent would.
 * <p>
 * A CRASH_* fault halts that JVM for real - no shutdown hooks, no cleanup -
 * and {@link #restart()} starts it again on the same transaction log, so
 * Atomikos recovery and the saga sweep run exactly as after a production crash.
 * (Halting the test JVM instead would kill the test runner too.)
 */
final class RunningApp implements AutoCloseable {

    private final List<String> command;
    private final Path output;
    private final RestClient http;
    private Process process;
    // where this launch's output starts: the file also holds the output of earlier launches
    private long outputOffset;

    private RunningApp(List<String> command, Path output, int port) {
        this.command = command;
        this.output = output;
        // the refund answers 409 or 202 on purpose: read the body, never throw
        this.http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    /** @param workDir holds the transaction log (which must survive a restart) and the application's output */
    static RunningApp start(PostgreSQLContainer postgres, ArtemisContainer artemis, Path workDir) {
        int port = freePort();
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                RefundApplication.class.getName(),
                "--server.port=" + port,
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.artemis.broker-url=" + artemis.getBrokerUrl(),
                "--spring.artemis.user=" + artemis.getUser(),
                "--spring.artemis.password=" + artemis.getPassword(),
                "--atomikos.properties.log-base-dir=" + workDir.resolve("transaction-logs"),
                // a crash can catch a listener in the middle of its own commit; recovery rolls that
                // branch back only after max-timeout (5 minutes by default), so shorten it to seconds
                "--atomikos.properties.max-timeout=12s",
                "--atomikos.properties.recovery.delay=3s",
                // abandoned refunds are compensated after seconds instead of half a minute
                "--refund.saga.stale-after=2s",
                "--refund.saga.sweep-interval=500ms"));
        RunningApp app = new RunningApp(command, workDir.resolve("application.log"), port);
        app.launch();
        return app;
    }

    RefundResult refund(String refundId, String orderId, Fault fault) {
        return http.post().uri("/refunds")
                .body(Map.of("refundId", refundId, "orderId", orderId, "fault", fault))
                .retrieve().body(RefundResult.class);
    }

    /** @return the HTTP status: 200 if all four steps were done */
    int naiveRefund(String orderId, Fault fault) {
        return http.post().uri("/naive-refunds")
                .body(Map.of("orderId", orderId, "fault", fault))
                .retrieve().toBodilessEntity().getStatusCode().value();
    }

    /** Refunds with a CRASH_* fault and waits until the JVM has halted at that point. */
    void refundAndCrash(String refundId, String orderId, Fault crash) {
        expectCrash(crash, () -> refund(refundId, orderId, crash));
    }

    void naiveRefundAndCrash(String orderId, Fault crash) {
        expectCrash(crash, () -> naiveRefund(orderId, crash));
    }

    /** Starts the application again on the same data and transaction log: recovery runs. */
    void restart() {
        launch();
    }

    /** What every system knows (see {@code GET /state}). */
    @SuppressWarnings("unchecked")
    Map<String, List<Map<String, Object>>> state() {
        return http.get().uri("/state").retrieve().body(Map.class);
    }

    @Override
    public void close() {
        if (process != null) {
            process.destroyForcibly();
        }
    }

    private void launch() {
        try {
            outputOffset = Files.exists(output) ? Files.size(output) : 0;
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(output.toFile()))
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the application", e);
        }
        await().atMost(Duration.ofSeconds(60)).ignoreExceptions()
               .until(() -> http.get().uri("/state").retrieve().toBodilessEntity().getStatusCode().is2xxSuccessful());
    }

    private void expectCrash(Fault crash, Runnable call) {
        try {
            call.run();
        } catch (RuntimeException expected) {
            // the connection drops when the JVM halts
        }
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("the application halted").isTrue();
            assertThat(process.exitValue()).as("exit code of the halt").isEqualTo(1);
            // only the planned crash may end the application: anything else fails the test
            assertThat(outputOfThisLaunch())
                    .as("application output").contains("Injected fault " + crash + ": halting the JVM");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String outputOfThisLaunch() throws IOException {
        byte[] all = Files.readAllBytes(output);
        return new String(all, (int) outputOffset, all.length - (int) outputOffset, StandardCharsets.UTF_8);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

}
