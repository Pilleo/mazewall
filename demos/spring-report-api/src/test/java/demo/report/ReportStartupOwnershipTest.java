package demo.report;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportStartupOwnershipTest {
    @TempDir Path root;

    @Test void failedPostAcquisitionStartupRetainsOriginalUntilRealRetirement() throws Exception {
        java.nio.file.Files.copy(Path.of(System.getProperty("example.lifecycleJar")), root.resolve("application.jar"));
        java.nio.file.Files.createDirectories(root.resolve("templates"));
        java.nio.file.Files.writeString(root.resolve("templates/approved.txt"), "fixture");
        var output = root.resolve("post-acquisition.log");
        var javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(javaExecutable, "--enable-native-access=ALL-UNNAMED",
                "-Dreport.approverPassword=local-evaluation-only", "-Dreport.readerPassword=local-evaluation-only",
                "-Dloader.main=demo.report.ReportStartupOwnershipProbe", "-cp", root.resolve("application.jar").toString(),
                "org.springframework.boot.loader.launch.PropertiesLauncher", "post-acquisition", root.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        process.getOutputStream().close();
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "probe did not finish");
            assertEquals(0, process.exitValue(), java.nio.file.Files.readString(output));
            String result = java.nio.file.Files.readString(output);
            assertTrue(result.contains("STARTUP_RETAINED_OK"));
            result.lines().filter(line -> line.startsWith("STARTUP_")).forEach(System.out::println);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test void failedRefreshRetainsOpaqueContextWithoutClaimingCompletion() throws Exception {
        java.nio.file.Files.copy(Path.of(System.getProperty("example.lifecycleJar")), root.resolve("application.jar"));
        java.nio.file.Files.createDirectories(root.resolve("templates"));
        java.nio.file.Files.writeString(root.resolve("templates/approved.txt"), "fixture");
        var output = root.resolve("refresh-failure.log");
        var javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(javaExecutable, "--enable-native-access=ALL-UNNAMED",
                "-Dreport.approverPassword=local-evaluation-only", "-Dreport.readerPassword=local-evaluation-only",
                "-Dloader.main=demo.report.ReportStartupOwnershipProbe", "-cp", root.resolve("application.jar").toString(),
                "org.springframework.boot.loader.launch.PropertiesLauncher", "refresh-failure", root.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        process.getOutputStream().close();
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "probe did not finish");
            String result = java.nio.file.Files.readString(output);
            assertEquals(0, process.exitValue(), result);
            assertTrue(result.contains("STARTUP_REFRESH_UNKNOWN_OK"));
            assertFalse(result.contains("REPORT_READY"));
            result.lines().filter(line -> line.startsWith("STARTUP_")).forEach(System.out::println);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test void actualCleanupErrorPreservesIdentityAndUnknownDoesNotReplay() throws Exception {
        java.nio.file.Files.copy(Path.of(System.getProperty("example.lifecycleJar")), root.resolve("application.jar"));
        java.nio.file.Files.createDirectories(root.resolve("templates"));
        java.nio.file.Files.writeString(root.resolve("templates/approved.txt"), "fixture");
        var output = root.resolve("cleanup-fatal.log");
        var javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(javaExecutable, "--enable-native-access=ALL-UNNAMED",
                "-Dreport.approverPassword=local-evaluation-only", "-Dreport.readerPassword=local-evaluation-only",
                "-Dloader.main=demo.report.ReportStartupOwnershipProbe", "-cp", root.resolve("application.jar").toString(),
                "org.springframework.boot.loader.launch.PropertiesLauncher", "cleanup-fatal", root.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        process.getOutputStream().close();
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "probe did not finish");
            String result = java.nio.file.Files.readString(output);
            assertEquals(0, process.exitValue(), result);
            assertTrue(result.contains("STARTUP_FATAL_UNKNOWN_OK"));
            result.lines().filter(line -> line.startsWith("STARTUP_")).forEach(System.out::println);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test void originalStarterCannotReenterAnActualUnclaimedResourceRetry() throws Exception {
        java.nio.file.Files.copy(Path.of(System.getProperty("example.lifecycleJar")), root.resolve("application.jar"));
        java.nio.file.Files.createDirectories(root.resolve("templates"));
        java.nio.file.Files.writeString(root.resolve("templates/approved.txt"), "fixture");
        var output = root.resolve("reentrant.log");
        var javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(javaExecutable, "--enable-native-access=ALL-UNNAMED",
                "-Dreport.approverPassword=local-evaluation-only", "-Dreport.readerPassword=local-evaluation-only",
                "-Dloader.main=demo.report.ReportStartupOwnershipProbe", "-cp", root.resolve("application.jar").toString(),
                "org.springframework.boot.loader.launch.PropertiesLauncher", "reentrant-unclaimed", root.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        process.getOutputStream().close();
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "probe did not finish");
            String result = java.nio.file.Files.readString(output);
            assertEquals(0, process.exitValue(), result);
            assertTrue(result.contains("STARTUP_REENTRANT_RETRY_OK"));
            result.lines().filter(line -> line.startsWith("STARTUP_")).forEach(System.out::println);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test void actualReviewInputReadFailureRetiresTheUnacquiredRoot() throws Exception {
        java.nio.file.Files.createDirectory(root.resolve("trusted-review.properties"));
        var output = root.resolve("io-failure.log");
        var javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(javaExecutable, "-Dloader.main=demo.report.ReportStartupOwnershipProbe",
                "-cp", System.getProperty("example.lifecycleJar"), "org.springframework.boot.loader.launch.PropertiesLauncher",
                "io-read-failure", root.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        process.getOutputStream().close();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            String result = java.nio.file.Files.readString(output);
            assertEquals(0, process.exitValue(), result);
            assertTrue(result.contains("STARTUP_REAL_IO_FAILURE_OK"));
            result.lines().filter(line -> line.startsWith("STARTUP_")).forEach(System.out::println);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test void suppressionDisabledPrimaryCannotLoseActualOwnedCloseError() throws Exception {
        java.nio.file.Files.copy(Path.of(System.getProperty("example.lifecycleJar")), root.resolve("application.jar"));
        java.nio.file.Files.createDirectories(root.resolve("templates"));
        java.nio.file.Files.writeString(root.resolve("templates/approved.txt"), "fixture");
        var output = root.resolve("no-suppression.log");
        var javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(javaExecutable, "--enable-native-access=ALL-UNNAMED",
                "-Dreport.approverPassword=local-evaluation-only", "-Dreport.readerPassword=local-evaluation-only",
                "-Dloader.main=demo.report.ReportStartupOwnershipProbe", "-cp", root.resolve("application.jar").toString(),
                "org.springframework.boot.loader.launch.PropertiesLauncher", "suppression-disabled", root.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        process.getOutputStream().close();
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "probe did not finish");
            String result = java.nio.file.Files.readString(output);
            assertEquals(0, process.exitValue(), result);
            assertTrue(result.contains("STARTUP_SUPPRESSION_DISABLED_OK"));
            result.lines().filter(line -> line.startsWith("STARTUP_")).forEach(System.out::println);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test void failedPreEffectStartupReleasesRootButKeepsAdmissionSpent() throws Exception {
        var output = root.resolve("probe.log");
        var javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(javaExecutable, "-Dloader.main=demo.report.ReportStartupOwnershipProbe",
                "-cp", System.getProperty("example.lifecycleJar"),
                "org.springframework.boot.loader.launch.PropertiesLauncher", root.resolve("missing").toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), java.nio.file.Files.readString(output));
            String result = java.nio.file.Files.readString(output);
            assertTrue(result.contains("STARTUP_PRE_EFFECT_OK"));
            result.lines().filter(line -> line.startsWith("STARTUP_")).forEach(System.out::println);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }
}
