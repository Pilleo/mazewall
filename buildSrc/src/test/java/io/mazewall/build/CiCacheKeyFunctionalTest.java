package io.mazewall.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Real, exclusively owned Git fixtures; public entrypoint is used by the focused Gradle task. */
public final class CiCacheKeyFunctionalTest {
    private CiCacheKeyFunctionalTest() {}

    public static void main(String[] args) {
        if (args.length != 1) throw new AssertionError("Expected the actual repository root");
        AssertionError failure = null;
        try {
            verify(Path.of(args[0]));
        } catch (AssertionError helperFailure) {
            failure = helperFailure;
        }
        try {
            CiCachePolicyTest.run();
        } catch (AssertionError | IllegalStateException policyFailure) {
            if (failure == null) throw policyFailure;
            failure.addSuppressed(policyFailure);
        }
        if (failure != null) throw failure;
        VerificationWorkflowInputFunctionalTest.verify(Path.of(args[0]));
    }

    static void verify(Path repository) {
        Path helper = repository.resolve("scripts/ci_cache_key.sh");
        if (!Files.isExecutable(helper)) throw new AssertionError("Missing executable cache-key helper: " + helper);
        try (Fixture fixture = new Fixture()) {
            Path root = fixture.directory.resolve("checkout");
            Files.createDirectories(root);
            fixture.git(root, "init", "--quiet");
            write(root, "scripts/ci_cache_key.sh", Files.readString(helper));
            root.resolve("scripts/ci_cache_key.sh").toFile().setExecutable(true, true);
            List<String> inputs = List.of(
                "build.gradle.kts", "nested/build.gradle", "nested/settings.gradle.kts",
                "gradle.properties", "nested/gradle.properties", "gradle.lockfile", "nested/gradle.lockfile",
                "gradle/libs.versions.toml", "gradle/verification-metadata.xml",
                "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
                "Containerfile", "infra/dev/Containerfile", ".github/workflows/ci.yml",
                "scripts/run_containerized_tests.sh", "scripts/ci_cache_key.sh",
                "space name/ü\nname.gradle.kts");
            for (String input : inputs) {
                if (!input.equals("scripts/ci_cache_key.sh")) write(root, input, "original " + input + "\n");
            }
            write(root, "src/Main.kt", "unrelated source\n");
            write(root, ".gitignore", "build/\n.gradle/\n");
            fixture.git(root, "add", ".");
            String baseline = fixture.key(root);
            equal(true, baseline.matches("[0-9a-f]{64}\\n"));

            write(root, "buildSrc/build/kotlin-dsl/generated.gradle.kts", "generated\n");
            write(root, ".gradle/caches/instrumented.gradle-instrumented-classpath.marker", "marker\n");
            equal(baseline, fixture.key(root));
            write(root, "scratch.gradle.kts", "untracked config\n");
            write(root, "src/Main.kt", "changed unrelated source\n");
            equal(baseline, fixture.key(root));

            for (String input : inputs) {
                Path path = root.resolve(input);
                byte[] original = Files.readAllBytes(path);
                Files.writeString(path, Files.readString(path) + "\n# relevant change\n");
                equal(false, baseline.equals(fixture.key(root)));
                Files.write(path, original);
                equal(baseline, fixture.key(root));
            }
            fixture.git(root, "add", "scratch.gradle.kts");
            equal(false, baseline.equals(fixture.key(root)));
            fixture.git(root, "rm", "--cached", "--quiet", "scratch.gradle.kts");
            equal(baseline, fixture.key(root));
            fixture.git(root, "rm", "--cached", "--quiet", "nested/build.gradle");
            equal(false, baseline.equals(fixture.key(root)));
            fixture.git(root, "add", "nested/build.gradle");
            equal(baseline, fixture.key(root));
            fixture.git(root, "mv", "nested/build.gradle", "nested/renamed.gradle");
            equal(false, baseline.equals(fixture.key(root)));
            fixture.git(root, "mv", "nested/renamed.gradle", "nested/build.gradle");
            equal(baseline, fixture.key(root));

            Path foreign = Files.createDirectories(fixture.directory.resolve("foreign"));
            fixture.git(foreign, "init", "--quiet");
            write(foreign, "build.gradle.kts", "foreign repository\n");
            fixture.git(foreign, "add", ".");
            Map<String, String> overrides = Map.of(
                "GIT_DIR", foreign.resolve(".git").toString(),
                "GIT_WORK_TREE", foreign.toString(),
                "GIT_INDEX_FILE", foreign.resolve(".git/index").toString(),
                "GIT_PREFIX", "foreign/", "GIT_COMMON_DIR", foreign.resolve(".git").toString());
            equal(baseline, fixture.run(root.resolve("nested"), overrides,
                "bash", root.resolve("scripts/ci_cache_key.sh").toString()).success());

            Path alias = root.resolve("alias.gradle.kts");
            Files.createSymbolicLink(alias, Path.of("nested/build.gradle"));
            fixture.git(root, "add", "alias.gradle.kts");
            String withAlias = fixture.key(root);
            equal(false, baseline.equals(withAlias));
            write(root, "nested/build.gradle", "changed contained target\n");
            equal(false, withAlias.equals(fixture.key(root)));
            write(root, "nested/build.gradle", "original nested/build.gradle\n");
            Files.delete(alias);
            Files.createSymbolicLink(alias, foreign.resolve("build.gradle.kts"));
            fixture.invalid(root, Map.of());
            Files.delete(alias);
            Files.createSymbolicLink(alias, Path.of("absent.gradle.kts"));
            fixture.invalid(root, Map.of());
            Files.delete(alias);
            Files.createSymbolicLink(alias, Path.of("alias.gradle.kts"));
            fixture.invalid(root, Map.of());
            Files.delete(alias);
            Files.createDirectory(alias);
            fixture.invalid(root, Map.of());
            Files.delete(alias);
            fixture.run(root, Map.of(), "mkfifo", alias.toString()).success();
            fixture.invalid(root, Map.of());
            Files.delete(alias);
            fixture.git(root, "rm", "--cached", "--quiet", "alias.gradle.kts");

            Path unreadable = root.resolve("gradle.properties");
            var permissions = Files.getPosixFilePermissions(unreadable);
            try {
                Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("---------"));
                if (Files.isReadable(unreadable)) throw new AssertionError("Permission fixture requires an unprivileged UID");
                fixture.invalid(root, Map.of());
            } finally {
                Files.setPosixFilePermissions(unreadable, permissions);
            }
            Path missing = root.resolve("gradle.lockfile");
            Files.delete(missing);
            fixture.invalid(root, Map.of());
            write(root, "gradle.lockfile", "original gradle.lockfile\n");

            Path bin = Files.createDirectories(fixture.directory.resolve("bin"));
            write(bin, "sha256sum", "#!/bin/sh\nprintf partial\nexit 19\n");
            bin.resolve("sha256sum").toFile().setExecutable(true, true);
            Map<String, String> brokenPath = Map.of("PATH", bin + ":" + System.getenv("PATH"));
            fixture.invalid(root, brokenPath);
            write(bin, "sha256sum", "#!/bin/sh\nprintf 'not-a-digest\\n'\n");
            fixture.invalid(root, brokenPath);
            String realHash = fixture.run(root, Map.of(), "bash", "-c", "command -v sha256sum").success().strip();
            Path counter = bin.resolve("hash-count");
            write(bin, "sha256sum", "#!/bin/sh\ncount=0\nif [ -f \"" + counter + "\" ]; then read count < \""
                + counter + "\"; fi\ncount=$((count + 1))\nprintf '%s\\n' \"$count\" > \"" + counter + "\"\n"
                + "if [ \"$count\" -eq " + (inputs.size() + 1) + " ]; then printf partial; exit 29; fi\n"
                + "exec \"" + realHash + "\"\n");
            fixture.invalid(root, brokenPath);
            equal(Integer.toString(inputs.size() + 1), Files.readString(counter).strip());
            Files.delete(bin.resolve("sha256sum"));
            String realGit = fixture.run(root, Map.of(), "bash", "-c", "command -v git").success().strip();
            write(bin, "git", "#!/bin/sh\nif [ \"$1\" = ls-files ]; then printf partial; exit 31; fi\nexec \""
                + realGit + "\" \"$@\"\n");
            bin.resolve("git").toFile().setExecutable(true, true);
            fixture.invalid(root, brokenPath);
            write(bin, "git", "#!/bin/sh\nexit 23\n");
            fixture.invalid(root, brokenPath);
            Files.delete(bin.resolve("git"));
            fixture.git(root, "read-tree", "--empty");
            fixture.invalid(root, Map.of());
            Path gitDirectory = root.resolve(".git");
            Files.move(gitDirectory, root.resolve("not-git"));
            fixture.invalid(root, Map.of());
            System.out.println("CI cache key: tracked inputs, generated exclusions, invalidation, names, aliases, Git context and fail-closed errors passed.");
        } catch (Exception failure) {
            throw new AssertionError("CI cache-key functional contract failed", failure);
        }
    }

    private static void write(Path root, String name, String value) throws IOException {
        Path target = root.resolve(name);
        Files.createDirectories(target.getParent());
        Files.writeString(target, value);
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    private record Result(int exitCode, String stdout, String stderr) {
        String success() {
            if (exitCode != 0) throw new AssertionError("Child failed (" + exitCode + "): " + stderr);
            return stdout;
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Path directory = Files.createTempDirectory(
            Files.isDirectory(Path.of("/tmp/opencode")) ? Path.of("/tmp/opencode") : Path.of(System.getProperty("java.io.tmpdir")),
            "ci-cache-key-");
        private final List<String> gitLocalNames;

        private Fixture() throws Exception {
            try {
                gitLocalNames = run(directory, Map.of(), "git", "rev-parse", "--local-env-vars").success().lines().toList();
            } catch (Exception | Error failure) {
                try {
                    close();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        private void git(Path root, String... args) throws Exception {
            var command = new ArrayList<>(List.of("git"));
            command.addAll(List.of(args));
            run(root, Map.of(), command.toArray(String[]::new)).success();
        }

        private String key(Path root) throws Exception {
            return run(root, Map.of(), "bash", root.resolve("scripts/ci_cache_key.sh").toString()).success();
        }

        private void invalid(Path root, Map<String, String> environment) throws Exception {
            Result result = run(root, environment, "bash", root.resolve("scripts/ci_cache_key.sh").toString());
            equal(0, result.stdout().length());
            equal(true, result.exitCode() != 0);
        }

        private Result run(Path cwd, Map<String, String> environment, String... command) throws Exception {
            Path stdout = Files.createTempFile(directory, "stdout-", ".log");
            Path stderr = Files.createTempFile(directory, "stderr-", ".log");
            var builder = new ProcessBuilder(command).directory(cwd.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
            if (gitLocalNames != null) gitLocalNames.forEach(builder.environment()::remove);
            builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
            builder.environment().putAll(environment);
            Process process = builder.start();
            boolean interrupted = false;
            try {
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out owned fixture child: " + List.of(command));
                }
                return new Result(process.exitValue(), Files.readString(stdout), Files.readString(stderr));
            } catch (InterruptedException failure) {
                interrupted = true;
                throw failure;
            } finally {
                try {
                    if (process.isAlive()) {
                        List<ProcessHandle> descendants = process.descendants().toList();
                        descendants.reversed().forEach(ProcessHandle::destroyForcibly);
                        try {
                            for (ProcessHandle child : descendants) child.onExit().get(5, TimeUnit.SECONDS);
                        } finally {
                            process.destroyForcibly();
                            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new AssertionError("Cannot reap owned fixture child");
                        }
                    }
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
        }

        @Override public void close() throws IOException {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
