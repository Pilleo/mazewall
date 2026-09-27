package io.mazewall.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;

/** Cache compiled native artifacts, never a CMake build tree, CTest result, or timing sample. */
@CacheableTask
public abstract class TierENativeBuildTask extends DefaultTask {
    private final ExecOperations execution;

    @Inject
    public TierENativeBuildTask(ExecOperations execution) {
        this.execution = execution;
    }

    @Internal
    public abstract DirectoryProperty getNativeDirectory();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getNativeSources();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getDockerExecutable();

    @Input
    public abstract Property<String> getDockerEndpoint();

    @Input
    public abstract Property<String> getImageDigest();

    @Input
    public abstract Property<String> getCompilerVersion();

    @Input
    public abstract Property<String> getJavaVersion();

    @Input
    public abstract Property<String> getCmakeVersion();

    @Input
    public abstract Property<String> getTargetArchitecture();

    @Input
    public abstract Property<String> getBuildType();

    @Input
    public abstract Property<Boolean> getUsdtEnabled();

    @OutputDirectory
    public abstract DirectoryProperty getArtifactDirectory();

    @TaskAction
    public final void buildNative() throws IOException {
        String image = getImageDigest().get();
        String variant = getBuildType().get();
        if (!image.matches("sha256:[a-f0-9]{64}") || !variant.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Native build needs an immutable image digest and validated build type");
        }
        Path root = getNativeDirectory().get().getAsFile().toPath().toRealPath();
        Path output = getArtifactDirectory().get().getAsFile().toPath();
        Path parent = output.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        String before = sourceDigest(root);
        List<String> targets = executableTargets(root.resolve("CMakeLists.txt"));
        Path working = Files.createTempDirectory(getTemporaryDir().toPath(), "native-build-");
        Path staged = Files.createTempDirectory(parent, ".tier-e-native-package-");
        try {
            String uid = Files.getAttribute(root, "unix:uid").toString();
            String gid = Files.getAttribute(root, "unix:gid").toString();
            String command = "cmake -S /workspace/profiler-native -B /workspace/native-build "
                + "-DCMAKE_BUILD_TYPE=" + variant + " -DMAZEWALL_ENABLE_TIER_E_USDT=" + getUsdtEnabled().get()
                + " && cmake --build /workspace/native-build -j2";
            execution.exec(spec -> {
                spec.commandLine(getDockerExecutable().get().getAsFile(), "run", "--rm", "--userns=host", "--user", uid + ":" + gid,
                    "-v", root + ":/workspace/profiler-native:ro",
                    "-v", working + ":/workspace/native-build",
                    "-w", "/workspace", image, "/bin/sh", "-c", command);
                spec.environment("DOCKER_HOST", getDockerEndpoint().get());
            }).assertNormalExitValue();
            if (!before.equals(sourceDigest(root))) {
                throw new IllegalStateException("Native source changed during Docker build");
            }
            var files = new ArrayList<String>();
            copyArtifact(working.resolve("libmazewall_stack_agent.so"), staged.resolve("libmazewall_stack_agent.so"), staged, files);
            for (String target : targets) {
                Path executable = working.resolve(target);
                if (!Files.isExecutable(executable)) throw new IllegalArgumentException("Native test binary is not executable: " + target);
                copyArtifact(executable, staged.resolve("test-binaries").resolve(target), staged, files);
            }
            Path classes = working.resolve("agent-test-classes");
            if (!Files.isDirectory(classes, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Native workload classes missing");
            }
            try (var entries = Files.walk(classes)) {
                for (Path file : entries.filter(path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                    if (!file.getFileName().toString().endsWith(".class") && !file.getFileName().toString().endsWith(".jar")) {
                        throw new IllegalArgumentException("Unexpected native workload output: " + file);
                    }
                    copyArtifact(file, staged.resolve("agent-test-classes").resolve(classes.relativize(file)), staged, files);
                }
            }
            if (!Files.isRegularFile(staged.resolve("agent-test-classes/AgentWorkload.class")) ||
                !Files.isRegularFile(staged.resolve("agent-test-classes/redefinition-agent.jar"))) {
                throw new IllegalArgumentException("Native benchmark workload or redefinition agent missing");
            }
            files.sort(String::compareTo);
            StringBuilder manifest = new StringBuilder("{\"schema_version\":1,\"source_digest\":\"").append(before)
                .append("\",\"image_id\":\"").append(image)
                .append("\",\"build_type\":\"").append(variant)
                .append("\",\"usdt\":").append(getUsdtEnabled().get())
                .append(",\"compiler\":\"").append(escape(getCompilerVersion().get()))
                .append("\",\"java\":\"").append(escape(getJavaVersion().get()))
                .append("\",\"cmake\":\"").append(escape(getCmakeVersion().get()))
                .append("\",\"architecture\":\"").append(escape(getTargetArchitecture().get()))
                .append("\",\"policy_eligibility\":false,\"files\":{");
            for (int index = 0; index < files.size(); index++) {
                if (index > 0) manifest.append(',');
                String relative = files.get(index);
                manifest.append('"').append(escape(relative)).append("\":\"")
                    .append(hash(Files.readAllBytes(staged.resolve(relative)))).append('"');
            }
            Files.writeString(staged.resolve("manifest.json"), manifest.append("}}\n").toString());
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException("Native artifact output is not an owned directory");
                }
                try (var entries = Files.list(output)) {
                    if (entries.findAny().isPresent()) {
                        requireVerifiedPriorPackage(output);
                    }
                }
                deleteTree(output);
            }
            Files.move(staged, output, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) deleteTree(staged);
            deleteTree(working);
        }
    }

    private String sourceDigest(Path root) throws IOException {
        var paths = new ArrayList<Path>();
        for (var input : getNativeSources().getFiles()) {
            Path path = input.toPath();
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Invalid native source: " + path);
            paths.add(path);
        }
        paths.sort(Comparator.comparing(path -> root.relativize(path).toString()));
        MessageDigest digest = sha256();
        for (Path path : paths) {
            digest.update(root.relativize(path).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Files.readAllBytes(path));
            digest.update((byte) 0);
        }
        return hex(digest.digest());
    }

    private static List<String> executableTargets(Path cmake) throws IOException {
        Matcher matches = Pattern.compile("(?m)^add_executable\\(([A-Za-z0-9_]+)\\s").matcher(Files.readString(cmake));
        var targets = new ArrayList<String>();
        while (matches.find()) targets.add(matches.group(1));
        if (targets.isEmpty() || targets.size() != targets.stream().distinct().count()) {
            throw new IllegalArgumentException("Native CMake test target inventory missing or ambiguous");
        }
        return targets;
    }

    private static void copyArtifact(Path from, Path to, Path root, List<String> files) throws IOException {
        if (!Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Missing native build artifact: " + from);
        Files.createDirectories(to.getParent());
        Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
        files.add(root.relativize(to).toString().replace('\\', '/'));
    }

    /** An interrupted or operator-extended package is evidence, not disposable build scratch. */
    private static void requireVerifiedPriorPackage(Path output) throws IOException {
        Path manifest = output.resolve("manifest.json");
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Refusing to replace unowned native artifact directory");
        }
        String document = Files.readString(manifest);
        String marker = "\"files\":{";
        int begin = document.indexOf(marker);
        int end = document.lastIndexOf('}');
        if (!document.contains("\"schema_version\":1") || begin < 0 || end <= begin) {
            throw new IllegalArgumentException("Native artifact ownership manifest is invalid");
        }
        String body = document.substring(begin + marker.length(), end - 1);
        Matcher entries = Pattern.compile("\"([A-Za-z0-9._/$-]+)\":\"([a-f0-9]{64})\"").matcher(body);
        var declared = new java.util.HashSet<String>();
        while (entries.find()) {
            String name = entries.group(1);
            Path file = output.resolve(name).normalize();
            if (!file.startsWith(output) || !declared.add(name) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !hash(Files.readAllBytes(file)).equals(entries.group(2))) {
                throw new IllegalArgumentException("Native artifact was altered or escaped its package");
            }
        }
        if (declared.isEmpty() || !entries.replaceAll("").replace(",", "").isBlank()) {
            throw new IllegalArgumentException("Native artifact file inventory is malformed");
        }
        try (var paths = Files.walk(output)) {
            for (Path path : paths.toList()) {
                if (path.equals(output) || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                String name = output.relativize(path).toString().replace('\\', '/');
                if (!name.equals("manifest.json") && !declared.contains(name)) {
                    throw new IllegalArgumentException("Native artifact contains externally owned files: " + name);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (var entries = Files.walk(root)) {
            for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String hash(byte[] bytes) {
        return hex(sha256().digest(bytes));
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(String.format("%02x", b & 0xff));
        return value.toString();
    }
}
