package io.mazewall.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure filesystem validator for backlog totals, identity, links, status and index membership. */
final class BacklogIndexValidator {
    private static final Set<String> ACTIVE_STATUSES = Set.of("open", "deferred");
    private static final Pattern SUMMARY = Pattern.compile(
        "^\\*Total Active Issues:\\* (\\d+) \\| \\*Total Resolved Issues:\\* (\\d+)$", Pattern.MULTILINE);
    private static final Pattern ROW = Pattern.compile("^\\| \\[[^]]+]\\(([^)]+\\.md)\\) \\|", Pattern.MULTILINE);
    private static final Pattern STATUS = Pattern.compile("(?m)^status:\\s*[\"']?([^\"'\\s]+)");
    private static final Pattern ID = Pattern.compile("(?m)^<!-- id:\\s*([^\\s]+)");

    private BacklogIndexValidator() {}

    static void validate(Path index) throws IOException {
        Path backlog = index.getParent();
        String text = Files.readString(index, StandardCharsets.UTF_8);
        Matcher summary = SUMMARY.matcher(text);
        if (!summary.find()) throw new IllegalArgumentException("Backlog index must contain exactly one total summary");
        int declaredActive = Integer.parseInt(summary.group(1));
        int declaredResolved = Integer.parseInt(summary.group(2));
        if (summary.find()) throw new IllegalArgumentException("Backlog index must contain exactly one total summary");

        List<Path> active = new ArrayList<>();
        List<Path> resolved = new ArrayList<>();
        Map<String, Path> identities = new HashMap<>();
        try (var paths = Files.walk(backlog)) {
            paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().startsWith("issue-"))
                .filter(path -> path.getFileName().toString().endsWith(".md"))
                .sorted()
                .forEach(path -> classify(backlog, path, active, resolved, identities));
        }

        Set<Path> indexed = new HashSet<>();
        Matcher row = ROW.matcher(text);
        while (row.find()) {
            Path target = backlog.resolve(row.group(1)).normalize();
            if (!target.startsWith(backlog)) throw new IllegalArgumentException("Backlog link escapes index directory: " + row.group(1));
            if (!Files.isRegularFile(target)) throw new IllegalArgumentException("Broken backlog link: " + row.group(1));
            if (!indexed.add(target)) throw new IllegalArgumentException("Duplicate backlog index entry: " + row.group(1));
        }

        Set<Path> activeSet = Set.copyOf(active);
        if (!indexed.equals(activeSet)) {
            Set<Path> missing = new HashSet<>(activeSet);
            missing.removeAll(indexed);
            Set<Path> stale = new HashSet<>(indexed);
            stale.removeAll(activeSet);
            throw new IllegalArgumentException("Backlog index membership mismatch; missing=" + relative(backlog, missing)
                + ", non-active=" + relative(backlog, stale));
        }
        if (declaredActive != active.size() || declaredResolved != resolved.size()) {
            throw new IllegalArgumentException("Backlog totals are stale: declared active/resolved=" + declaredActive + "/"
                + declaredResolved + ", actual=" + active.size() + "/" + resolved.size());
        }
    }

    private static void classify(Path backlog, Path path, List<Path> active, List<Path> resolved, Map<String, Path> identities) {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            Matcher status = STATUS.matcher(text);
            if (!status.find()) throw new IllegalArgumentException("Backlog issue has no status: " + backlog.relativize(path));
            String statusName = status.group(1).toLowerCase(java.util.Locale.ROOT);
            if (!"resolved".equals(statusName) && !ACTIVE_STATUSES.contains(statusName)) {
                throw new IllegalArgumentException("Unknown backlog status " + status.group(1) + ": " + backlog.relativize(path));
            }
            boolean resolvedLocation = path.startsWith(backlog.resolve("resolved"));
            boolean resolvedStatus = "resolved".equals(statusName);
            if (resolvedLocation != resolvedStatus) {
                throw new IllegalArgumentException("Backlog status/location mismatch: " + backlog.relativize(path));
            }
            Matcher id = ID.matcher(text);
            String identity = id.find() ? id.group(1) : path.getFileName().toString().replaceFirst("\\.md$", "");
            Path prior = identities.putIfAbsent(identity, path);
            if (prior != null) throw new IllegalArgumentException("Duplicate backlog issue id " + identity + ": "
                + backlog.relativize(prior) + " and " + backlog.relativize(path));
            (resolvedLocation ? resolved : active).add(path);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot read backlog issue " + path, failure);
        }
    }

    private static List<String> relative(Path root, Set<Path> paths) {
        return paths.stream().map(root::relativize).map(Path::toString).sorted().toList();
    }
}
