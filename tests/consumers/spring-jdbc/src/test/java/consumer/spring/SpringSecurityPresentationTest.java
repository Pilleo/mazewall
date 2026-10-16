package consumer.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Tag("presentation")
@DisplayName("07 — Preserve Spring Security context and transaction semantics on owned workers")
class SpringSecurityPresentationTest {
    @TempDir Path root;

    @Test
    @DisplayName("07.1 — Admin/Bob authorization, SQL rollback and context restoration use the real published adapter")
    void principalsTransactionsAndPhysicalEnd() throws Exception {
        Files.writeString(root.resolve("template.txt"), "approved template");
        Files.writeString(root.resolve("sibling.txt"), "private sibling");
        launch("measure-security");
        var review = new Properties();
        review.setProperty("reviewer", "external-known-security-presentation-fixture-reviewer");
        review.setProperty("jarSha256", sha(Path.of(System.getProperty("presentation.securityJar"))));
        review.setProperty("resourceGeneration", Files.readString(root.resolve("candidate-generation.txt")));
        for (var document : List.of("candidate", "other-candidate", "foreign-candidate", "stale-candidate", "wrong-member-candidate")) {
            String key = switch (document) {
                case "candidate" -> "documentSha256";
                case "other-candidate" -> "otherDocumentSha256";
                case "foreign-candidate" -> "foreignDocumentSha256";
                case "stale-candidate" -> "staleDocumentSha256";
                case "wrong-member-candidate" -> "wrongMemberDocumentSha256";
                default -> throw new AssertionError(document);
            };
            review.setProperty(key, sha(root.resolve(document + ".json")));
        }
        // Test authority is external to the measured application and restricted to this acquired fixture.
        try (var out = Files.newOutputStream(root.resolve("trusted-review.properties"))) { review.store(out, "Fixture only, not production authority"); }
        String result = launch("verify-security");
        for (String effect : List.of("NATIVE_SECURITY_DELEGATION_PASS", "postauth-real-SQL-rollback", "clean-context",
                "OBSERVED_FILESYSTEM_OPEN_DENIED mutate", "OBSERVED_FILESYSTEM_OPEN_DENIED sibling",
                "STALE_AND_WRONG_MEMBER_REJECTED bodyEntries=0", "NATIVE_ORDINARY_JAR_PASS")) {
            assertTrue(result.contains(effect), "Missing actual checked effect: " + effect + "\n" + result);
        }
        assertEquals("approved template", Files.readString(root.resolve("template.txt")));
        assertEquals("private sibling", Files.readString(root.resolve("sibling.txt")));
        assertEquals(review.getProperty("jarSha256"), sha(Path.of(System.getProperty("presentation.securityJar"))));
        System.out.println("RESULT: Admin commits; Bob post-authorization rolls back real SQL; same worker restores context after error/denial/cancel");
        System.out.println("CLEANUP: real captured worker/resources retired; no caller context or Security state leaked");
        System.out.println("LIMIT: ordinary-JAR optional Security proof, separate from Security-free Boot packaging; fixture review is not production approval");
    }

    private String launch(String mode) throws Exception {
        Path log = root.resolve(mode + ".log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("presentation.classpath"),
                "consumer.spring.NativeJdbcConsumer", mode, root.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Owned native Security process exceeded deadline");
            String output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            output.lines().filter(line -> line.startsWith("NATIVE_") || line.startsWith("OBSERVED_") || line.startsWith("STALE_"))
                    .forEach(System.out::println);
            return output;
        } finally {
            if (process.isAlive()) process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Owned native Security process did not retire");
        }
    }

    private static String sha(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
