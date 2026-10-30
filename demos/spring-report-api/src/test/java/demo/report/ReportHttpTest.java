package demo.report;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("native")
class ReportHttpTest {
    @TempDir Path root;
    private static final String PASSWORD = "local-evaluation-only";
    private static final String REPORT_TEXT = "Checkout deployment readiness\nService: checkout\nPool maximum: 20\nOwner: Ada é🙂\nSynthetic evaluation report, not production telemetry.";
    private String jarProperty = "example.bootJar";

    @Test @Tag("presentation") void actualHttpSecurityJdbcDenialRecoveryAndShutdown() throws Exception {
        prepare();
        Process server = launch("serve", "0").redirectOutput(root.resolve("serve.log").toFile()).start();
        try {
            long end = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            String ready = null;
            while (System.nanoTime() < end && server.isAlive()) {
                try (var lines = Files.lines(root.resolve("serve.log"))) {
                    ready = lines.filter(it -> it.startsWith("REPORT_READY port=")).findFirst().orElse(null);
                }
                if (ready != null) break;
                Thread.sleep(50);
            }
            assertNotNull(ready, Files.readString(root.resolve("serve.log")));
            int port = Integer.parseInt(ready.substring("REPORT_READY port=".length()));
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                URI base = URI.create("http://127.0.0.1:" + port);
                assertEquals(200, request(client, base, "/actuator/health/readiness", null).statusCode());
                var unauthenticated = request(client, base, "/reports/approved", null);
                assertEquals(401, unauthenticated.statusCode());
                System.out.println("AUTHENTICATION status=" + unauthenticated.statusCode() + " (actual unauthenticated HTTP response)");
                checkResponse(request(client, base, "/reports/approved", "approver"), 200, "COMMITTED", true, REPORT_TEXT);
                checkResponse(request(client, base, "/reports/forbidden", "approver"), 422, "ROLLED_BACK", true, "CONTAINMENT_REFUSED");
                checkResponse(request(client, base, "/reports/approved", "reader"), 403, "ROLLED_BACK", true, "AUTHORIZATION_DENIED");
                checkResponse(request(client, base, "/reports/approved", "approver"), 200, "COMMITTED", true, REPORT_TEXT);
                assertEquals(403, request(client, base, "/ordinary", "reader").statusCode());
                assertEquals(200, request(client, base, "/ordinary", "approver").statusCode());
                server.getOutputStream().write("drain\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                server.getOutputStream().flush();
                long drainEnd = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (request(client, base, "/actuator/health/readiness", null).statusCode() == 200 && System.nanoTime() < drainEnd) Thread.sleep(20);
                assertEquals(503, request(client, base, "/actuator/health/readiness", null).statusCode());
                checkResponse(request(client, base, "/reports/approved", "approver"), 503, "UNKNOWN", false, "BUSY");
            }
            server.getOutputStream().write("stop\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            server.getOutputStream().flush();
            assertTrue(server.waitFor(15, TimeUnit.SECONDS), "Owned server did not stop");
            String log = Files.readString(root.resolve("serve.log"));
            assertEquals(0, server.exitValue(), log);
            assertTrue(log.contains("REPORT_RETIRED actualWorker="), log);
            assertTrue(log.contains("REPORT_AUDIT committed=2 bodyEntries=4 cleanContexts=4"), log);
            assertFalse(log.contains("DUMMY_PRIVATE_CANARY"));
            log.lines().filter(line -> line.startsWith("REPORT_AUDIT ") || line.startsWith("REPORT_RETIRED ")).forEach(System.out::println);
            System.out.println("REPORT_PRESENTATION_COMPLETE actual HTTP, transaction outcomes and physical retirement verified; synthetic fixture review only");
        } finally {
            if (server.isAlive()) server.destroy();
            if (!server.waitFor(2, TimeUnit.SECONDS)) server.destroyForcibly();
            assertTrue(server.waitFor(5, TimeUnit.SECONDS));
        }
    }

    @Test void missingApprovalFailsBeforeTraffic() throws Exception {
        fixture();
        var log = root.resolve("unapproved.log");
        Process process = launch("serve", "0").redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            assertNotEquals(0, process.exitValue());
            assertFalse(Files.readString(log).contains("REPORT_READY"));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        }
    }

    @Test void editedTemplateCannotReuseStaleDeploymentReview() throws Exception {
        prepare();
        Files.writeString(root.resolve("templates/approved.txt"), "Changed checkout readiness: maximumPoolSize=20");
        Path stop = Files.writeString(root.resolve("stale-stop.txt"), "stop\n");
        Path log = root.resolve("stale-template.log");
        Process process = launch("serve", "0").redirectInput(stop.toFile()).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            String output = Files.readString(log);
            assertNotEquals(0, process.exitValue(), output);
            assertFalse(output.contains("REPORT_READY"), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        }
    }

    @Test void cancelledDeliveryRetainsActualWorkerAndResourcesUntilCanonicalDrain() throws Exception {
        jarProperty = "example.lifecycleJar";
        prepare();
        var log = root.resolve("lifecycle.log");
        Process process = launch("verify").redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            String output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("REPORT_HELD_LIFECYCLE_OK caller=cancelled drain=incomplete resources=retained rollback=actual retirement=physical"), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        }
    }

    @Test void drainBetweenReadyCheckAndActualProxyDispatchIsBusyWithoutBodyEntry() throws Exception {
        jarProperty = "example.lifecycleJar";
        prepare();
        var log = root.resolve("drain-race.log");
        Process process = launch("verify-drain-race").redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            String output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("REPORT_DRAIN_RACE_OK status=503 body=not-entered transaction=not-started"), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        }
    }

    @Test void listenerOverrideAddressIsRejectedBeforeReady() throws Exception {
        assertListenerOverrideRejected("server.address", "127.0.0.2");
    }

    @Test void listenerOverridePortIsRejectedBeforeReady() throws Exception {
        assertListenerOverrideRejected("server.port", "-1");
    }

    @Test void listenerOverrideManagementPortIsRejectedBeforeReady() throws Exception {
        assertListenerOverrideRejected("management.server.port", "-1");
    }

    @Test void listenerOverrideManagementZeroIsRejectedBeforeReady() throws Exception {
        assertListenerOverrideRejected("management.server.port", "0");
    }

    private void assertListenerOverrideRejected(String name, String value) throws Exception {
        prepare();
        Path stop = Files.writeString(root.resolve("stop.txt"), "stop\n");
        Path log = root.resolve("listener-override.log");
        var launch = launch("serve", "0").redirectInput(stop.toFile()).redirectOutput(log.toFile());
        launch.command().add(1, "-D" + name + "=" + value);
        if (name.startsWith("management.")) launch.command().add(1, "-Dmanagement.server.address=127.0.0.1");
        Process process = launch.start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            String output = Files.readString(log);
            assertNotEquals(0, process.exitValue(), output);
            assertFalse(output.contains("REPORT_READY"), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        }
    }

    private void prepare() throws Exception {
        fixture();
        Path candidate = root.resolve("candidate.json");
        Process measure = launch("measure").redirectOutput(candidate.toFile()).start();
        try {
            assertTrue(measure.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, measure.exitValue(), Files.readString(candidate));
        } finally {
            if (measure.isAlive()) measure.destroyForcibly();
            assertTrue(measure.waitFor(5, TimeUnit.SECONDS));
        }
        var document = io.mazewall.enforcer.api.OperationPolicyDocument.Companion.fromJson(Files.readString(candidate));
        var review = new Properties();
        review.setProperty("reviewer", "explicit-native-test-fixture-not-production-approval");
        review.setProperty("documentSha256", document.sha256());
        review.setProperty("jarSha256", document.getBinding().getImplementationSha256());
        review.setProperty("resourceGeneration", document.getBinding().getResourceGeneration());
        try (var output = Files.newOutputStream(root.resolve("trusted-review.properties"))) { review.store(output, "Explicit test harness approval only"); }
    }
    private void fixture() throws Exception {
        Files.copy(Path.of(System.getProperty(jarProperty)), root.resolve("application.jar"));
        Path templates = Files.createDirectory(root.resolve("templates"));
        Files.writeString(templates.resolve("approved.txt"), REPORT_TEXT);
        Files.writeString(templates.resolve("forbidden.txt"), "DUMMY_PRIVATE_CANARY");
    }
    private ProcessBuilder launch(String... mode) {
        var args = new java.util.ArrayList<>(java.util.List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-Dreport.approverPassword=" + PASSWORD, "-Dreport.readerPassword=" + PASSWORD,
                "-jar", root.resolve("application.jar").toString(), mode[0], root.toString()));
        args.addAll(java.util.List.of(mode).subList(1, mode.length));
        return new ProcessBuilder(args).redirectErrorStream(true);
    }
    private static HttpResponse<String> request(HttpClient client, URI base, String path, String user) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15)).GET();
        if (user != null) builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static void checkResponse(HttpResponse<String> response, int status, String transaction, boolean entered, String body) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(transaction, response.headers().firstValue("X-Transaction-Outcome").orElseThrow());
        assertEquals(Boolean.toString(entered), response.headers().firstValue("X-Body-Entered").orElseThrow());
        assertDoesNotThrow(() -> java.util.UUID.fromString(response.headers().firstValue("X-Request-Id").orElseThrow()));
        assertTrue(response.body().contains(body), response.body());
        assertFalse(response.body().contains("DUMMY_PRIVATE_CANARY") || response.body().contains(".txt") || response.body().contains("demo.report"));
        System.out.println("REPORT_REQUEST id=" + response.headers().firstValue("X-Request-Id").orElseThrow()
                + " status=" + response.statusCode() + " transaction=" + response.headers().firstValue("X-Transaction-Outcome").orElseThrow()
                + " bodyEntered=" + response.headers().firstValue("X-Body-Entered").orElseThrow());
        System.out.println(response.body());
    }
}
