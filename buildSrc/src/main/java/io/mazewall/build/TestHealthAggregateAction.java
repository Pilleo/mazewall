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
import org.gradle.api.provider.Provider;
import org.gradle.api.Task;

/** Deterministically combines per-fork test-health fragments without treating old files as fresh. */
public final class TestHealthAggregateAction implements Action<Task> {
    private static final Pattern IDENTITY = Pattern.compile("\"taskPath\":\\s*\"([^\"]+)\"");
    private static final Pattern INVOCATION = Pattern.compile("\"invocationToken\":\\s*\"([^\"]+)\"");
    private static final Pattern EXECUTION_STATUS = Pattern.compile("\"executionStatus\":\\s*\"(completed|aborted|failed)\"");
    private final File fragments;
    private final File output;
    private final List<String> expectedTasks;
    private final Provider<TestHealthInvocation> invocation;

    public TestHealthAggregateAction(
        File fragments,
        File output,
        List<String> expectedTasks,
        Provider<TestHealthInvocation> invocation
    ) {
        this.fragments = fragments;
        this.output = output;
        this.expectedTasks = List.copyOf(expectedTasks);
        this.invocation = invocation;
    }

    @Override
    public void execute(Task task) {
        try {
            aggregate(fragments, output, expectedTasks, invocation.get().token());
        } catch (IOException failure) {
            throw new GradleException("Cannot aggregate test-health evidence", failure);
        }
    }

    static void aggregate(File fragments, File output, List<String> expectedTasks, String invocationToken) throws IOException {
        List<String> reports = new ArrayList<>();
        Set<String> executed = new HashSet<>();
        Set<String> staleTasks = new HashSet<>();
        if (fragments.isDirectory()) {
            try (var paths = Files.list(fragments.toPath())) {
                for (var path : paths.filter(candidate -> candidate.toString().endsWith(".json")).sorted().toList()) {
                    String report = Files.readString(path, StandardCharsets.UTF_8).trim();
                    Matcher identity = IDENTITY.matcher(report);
                    if (!identity.find()) throw new IllegalArgumentException("Malformed test-health fragment " + path);
                    String taskPath = identity.group(1);
                    Matcher invocation = INVOCATION.matcher(report);
                    Matcher status = EXECUTION_STATUS.matcher(report);
                    if (!status.find()) throw new IllegalArgumentException("Missing execution status in test-health fragment " + path);
                    if (!invocation.find() || !invocationToken.equals(invocation.group(1))) {
                        staleTasks.add(taskPath);
                    } else {
                        executed.add(taskPath);
                        reports.add(report);
                    }
                }
            }
        }
        List<String> absent = expectedTasks.stream().filter(task -> !executed.contains(task)).sorted().toList();
        if (!staleTasks.isEmpty()) {
            Files.deleteIfExists(output.toPath());
            throw new IllegalStateException(
                "Stale test-health fragments from another build invocation: current tasks "
                    + executed.stream().sorted().toList()
                    + ", stale tasks " + staleTasks.stream().sorted().toList()
                    + ", missing current tasks " + absent
            );
        }
        String json = "{\n  \"freshForks\": [\n    " + String.join(",\n    ", reports)
            + "\n  ],\n  \"invocationToken\": " + quote(invocationToken)
            + ",\n  \"currentTasks\": ["
            + executed.stream().sorted().map(TestHealthAggregateAction::quote).collect(java.util.stream.Collectors.joining(", "))
            + "],\n  \"cachedOrNotExecutedTasks\": ["
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
        StringBuilder escaped = new StringBuilder(value.length() + 16).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }
}
