package io.mazewall.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Task;

/** Validates the declared input file without capturing the root project. */
public final class ValidateBacklogIndexAction implements Action<Task> {
    private final File index;

    public ValidateBacklogIndexAction(File index) {
        this.index = index;
    }

    @Override
    public void execute(Task task) {
        try {
            long summaries = Pattern.compile("^\\*Total Active Issues:\\* \\d+ \\| \\*Total Resolved Issues:\\* \\d+$", Pattern.MULTILINE)
                .matcher(Files.readString(index.toPath(), StandardCharsets.UTF_8)).results().count();
            if (summaries != 1) {
                throw new GradleException("Backlog index must contain exactly one total summary, found " + summaries);
            }
        } catch (IOException failure) {
            throw new GradleException("Cannot read backlog index " + index, failure);
        }
    }
}
