package demo.boot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import static org.junit.jupiter.api.Assertions.*;

@Tag("presentation")
@DisplayName("06 — Adopt an explicit contract in a finished Spring Boot application")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BootPresentationTest {
    @TempDir Path root;

    @Test @Order(1)
    @DisplayName("06.1 — A measured candidate cannot authorize its own implementation")
    void measurementNeedsExternalReview() throws Exception {
        deploy();
        launch("measure", true);
        String output = launch("verify", false);
        assertTrue(output.contains("trusted-review.properties"), output);
        assertFalse(output.contains("JDBC_COMMITTED"), "Missing review must not enter the operation");
        assertEquals("", Files.readString(root.resolve("reports/quarterly.txt")));
        System.out.println("RESULT: candidate exists; external approval absent; required operation never enters");
    }

    @Test @Order(2)
    @DisplayName("06.2 — Reviewed singleton work commits SQL; denied work rolls back; physical cleanup remains owned")
    void approvedReportAndTransactions() throws Exception {
        deploy();
        launch("measure", true);
        var review = new Properties();
        review.setProperty("reviewer", "external-known-presentation-fixture-reviewer");
        review.setProperty("jarSha256", sha(root.resolve("application.jar")));
        review.setProperty("documentSha256", sha(root.resolve("candidate.json")));
        review.setProperty("resourceGeneration", Files.readString(root.resolve("candidate-generation.txt")));
        // JUnit is the external reviewer of this fixed private fixture; the application only measures.
        try (var out = Files.newOutputStream(root.resolve("trusted-review.properties"))) {
            review.store(out, "Known fixture only, not production approval");
        }
        String output = launch("verify", true);
        assertEquals("Reviewed quarterly template", Files.readString(root.resolve("template.txt")));
        assertEquals("Reviewed quarterly template: approved report", Files.readString(root.resolve("reports/quarterly.txt")));
        assertEquals(review.getProperty("jarSha256"), sha(root.resolve("application.jar")));
        for (String effect : List.of("BOOT_SECURITY_ABSENT", "REQUIRED_UNBOUND_REJECTED", "VIRTUAL_CALLER_PLATFORM_BODY",
                "JDBC_COMMITTED actualRow=approved", "DENIED_TEMPLATE_MUTATION", "DENIED_SIBLING_READ",
                "EXTERNAL_FILE_AND_HEAP_EFFECTS_SURVIVE_SQL_ROLLBACK", "CALLER_DELIVERY_NOT_PHYSICAL_END",
                "PHYSICAL_WORKER_RETIRED", "BOOT_NATIVE_PASS")) {
            assertTrue(output.contains(effect), "Missing actual checked effect: " + effect + "\n" + output);
        }
        System.out.println("RESULT: finished java-jar singleton + exact report file; real H2 commit/rollback; virtual caller/platform body");
        System.out.println("LIMIT: SQL rollback does not undo file effects; permission evidence is inferred; Security is absent in this Boot fixture");
        System.out.println("CLEANUP: captured worker and genuine resource owner physically retired");
    }

    private void deploy() throws Exception {
        Path source = Path.of(System.getProperty("presentation.jar"));
        String version = System.getProperty("presentation.version");
        Path repository = Path.of(System.getProperty("presentation.repository"));
        try (var jar = new JarFile(source.toFile())) {
            assertEquals("org.springframework.boot.loader.launch.JarLauncher", jar.getManifest().getMainAttributes().getValue("Main-Class"));
            assertEquals("4.1.1", jar.getManifest().getMainAttributes().getValue("Spring-Boot-Version"));
            for (String artifact : List.of("platform", "enforcer", "mazewall-spring-boot", "mazewall-spring-boot-starter")) {
                Path published = repository.resolve("io/mazewall/" + artifact + "/" + version + "/" + artifact + "-" + version + ".jar");
                var entry = jar.getJarEntry("BOOT-INF/lib/" + artifact + "-" + version + ".jar");
                assertNotNull(entry, artifact);
                try (var bytes = jar.getInputStream(entry)) { assertArrayEquals(Files.readAllBytes(published), bytes.readAllBytes(), artifact); }
            }
        }
        Files.copy(source, root.resolve("application.jar"));
        Files.writeString(root.resolve("template.txt"), "Reviewed quarterly template");
        Files.writeString(root.resolve("sibling.txt"), "unapproved sibling");
        Files.createDirectory(root.resolve("reports"));
        Files.createFile(root.resolve("reports/quarterly.txt"));
    }

    private String launch(String mode, boolean success) throws Exception {
        Path log = root.resolve(mode + ".log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-jar", root.resolve("application.jar").toString(), mode, root.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Owned finished Boot process exceeded its deadline");
            String output = Files.readString(log);
            if (success) assertEquals(0, process.exitValue(), output); else assertNotEquals(0, process.exitValue(), output);
            output.lines().filter(line -> line.contains("JDBC_") || line.contains("DENIED_") || line.contains("PHYSICAL_") || line.contains("BODY"))
                    .forEach(System.out::println);
            return output;
        } finally {
            if (process.isAlive()) process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Owned finished Boot process did not retire");
        }
    }

    private static String sha(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
