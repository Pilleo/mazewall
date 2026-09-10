package io.mazewall.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Task;

/** Deterministically combines per-fork test-health fragments without treating old files as fresh. */
public final class TestHealthAggregateAction implements Action<Task> {
    private static final Pattern IDENTITY = Pattern.compile("\"taskPath\":\\s*\"([^\"]+)\"");
    private final File fragments;
    private final File output;
    private final List<String> expectedTasks;

    public TestHealthAggregateAction(File fragments, File output, List<String> expectedTasks) {
        this.fragments = fragments;
        this.output = output;
        this.expectedTasks = List.copyOf(expectedTasks);
    }

    @Override
    public void execute(Task task) {
        try {
            aggregate(fragments, output, expectedTasks);
        } catch (IOException failure) {
            throw new GradleException("Cannot aggregate test-health evidence", failure);
        }
    }

    static void aggregate(File fragments, File output, List<String> expectedTasks) throws IOException {
        List<String> reports = new ArrayList<>();
        Set<String> executed = new HashSet<>();
        if (fragments.isDirectory()) {
            try (var paths = Files.list(fragments.toPath())) {
                for (var path : paths.filter(candidate -> candidate.toString().endsWith(".json")).sorted().toList()) {
                    String report = Files.readString(path, StandardCharsets.UTF_8).trim();
                    Matcher identity = IDENTITY.matcher(report);
                    if (!identity.find()) throw new IllegalArgumentException("Malformed test-health fragment " + path);
                    executed.add(identity.group(1));
                    reports.add(report);
                }
            }
        }
        List<String> absent = expectedTasks.stream().filter(task -> !executed.contains(task)).sorted().toList();
        String json = "{\n  \"freshForks\": [\n    " + String.join(",\n    ", reports)
            + "\n  ],\n  \"cachedOrNotExecutedTasks\": ["
            + absent.stream().map(TestHealthAggregateAction::quote).collect(java.util.stream.Collectors.joining(", "))
            + "]\n}\n";
        Files.createDirectories(output.toPath().getParent());
        var temporary = Files.createTempFile(output.toPath().getParent(), output.getName(), ".tmp");
        Files.writeString(temporary, json, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
