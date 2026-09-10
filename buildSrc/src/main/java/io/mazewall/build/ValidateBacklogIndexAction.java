package io.mazewall.build;

import java.io.File;
import java.io.IOException;
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
            BacklogIndexValidator.validate(index.toPath());
        } catch (IllegalArgumentException failure) {
            throw new GradleException(failure.getMessage(), failure);
        } catch (IOException failure) {
            throw new GradleException("Cannot validate backlog index " + index, failure);
        }
    }
}
