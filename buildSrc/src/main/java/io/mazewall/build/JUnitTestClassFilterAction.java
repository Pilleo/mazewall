package io.mazewall.build;

import java.util.stream.Collectors;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.tasks.testing.Test;

/** Restricts fresh-process candidates without loading classes or changing JUnit/CLI method filters. */
public final class JUnitTestClassFilterAction implements Action<Task> {
    @Override
    public void execute(Task task) {
        Test test = (Test) task;
        // Caller-owned file selection must not be broadened by the default-discovery optimization.
        if (!test.getIncludes().isEmpty()) return;
        var selected = FreshJvmClassSelector.selectTests(test.getTestClassesDirs().getFiles());
        if (selected.isEmpty()) throw new GradleException("No executable JUnit roots in " + test.getPath());
        test.setIncludes(selected.stream().map(name -> name.replace('.', '/') + ".class").collect(Collectors.toSet()));
    }
}
