package io.mazewall.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipFile;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Only these reviewed source families can enter the analyzer overlay. */
@CacheableTask
public abstract class GenerateSpotBugsSources extends DefaultTask {
    private static final List<String> SOURCES = List.of(
            "edu/umd/cs/findbugs/classfile/engine/bcel/ValueRangeAnalysisFactory.java",
            "edu/umd/cs/findbugs/ba/deref/UnconditionalValueDerefAnalysis.java",
            "edu/umd/cs/findbugs/detect/FindUselessObjects.java",
            "edu/umd/cs/findbugs/detect/FindBadCast2.java");

    @InputFiles @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getUpstreamArchive();

    @InputFiles @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract ConfigurableFileCollection getPatches();

    @OutputDirectory public abstract DirectoryProperty getOutputDirectory();
    @Inject protected abstract FileSystemOperations getFileSystemOperations();

    @TaskAction public void generate() throws IOException {
        var output = getOutputDirectory().get().getAsFile().toPath();
        getFileSystemOperations().delete(spec -> spec.delete(output));
        try (var archive = new ZipFile(getUpstreamArchive().getSingleFile())) {
            var seen = new java.util.HashSet<String>();
            for (var file : getPatches().getFiles().stream().sorted().toList()) {
                var patch = new Properties();
                try (var reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) { patch.load(reader); }
                String path = patch.getProperty("path");
                if (!SOURCES.contains(path) || !seen.add(path)) throw new GradleException("Unexpected analyzer patch target: " + path);
                var entry = archive.getEntry(path);
                if (entry == null) throw new GradleException("Upstream analyzer source absent: " + path);
                byte[] source;
                try (var input = archive.getInputStream(entry)) { source = input.readAllBytes(); }
                byte[] modified = reconstruct(source, patch);
                var target = output.resolve(path);
                Files.createDirectories(target.getParent());
                Files.write(target, modified);
            }
            if (!seen.equals(new java.util.HashSet<>(SOURCES))) throw new GradleException("Incomplete analyzer patch set: " + seen);
        }
    }

    static byte[] reconstruct(byte[] source, Properties patch) {
        requireDigest(source, patch.getProperty("sha256"));
        String result = new String(source, StandardCharsets.UTF_8);
        int changes = 0;
        for (int index = 1; patch.containsKey("before." + index); index++) {
            String before = patch.getProperty("before." + index);
            String after = patch.getProperty("after." + index);
            int position = result.indexOf(before);
            if (before.isEmpty() || after == null || before.equals(after) || position < 0
                    || result.indexOf(before, position + 1) >= 0) {
                throw new GradleException("Analyzer patch preimage is not unique: " + patch.getProperty("path") + " #" + index);
            }
            result = result.substring(0, position) + after + result.substring(position + before.length());
            changes++;
        }
        if (changes == 0) throw new GradleException("Empty analyzer patch");
        byte[] modified = result.getBytes(StandardCharsets.UTF_8);
        requireDigest(modified, patch.getProperty("resultSha256"));
        return modified;
    }

    private static void requireDigest(byte[] bytes, String expected) {
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!actual.equals(expected)) throw new GradleException("Unreviewed analyzer source digest: expected " + expected + ", actual " + actual);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("Required JDK SHA-256 unavailable", impossible);
        }
    }
}
