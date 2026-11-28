package io.mazewall.build;

import java.io.File;
import java.io.IOException;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Task;

/** Refreshes the backlog index totals without capturing the root project. */
public final class RefreshBacklogTotalsAction implements Action<Task> {
    private final File index;

    public RefreshBacklogTotalsAction(File index) {
        this.index = index;
    }

    @Override
    public void execute(Task task) {
        try {
            BacklogIndexValidator.refreshTotals(index.toPath());
        } catch (IllegalArgumentException failure) {
            throw new GradleException(failure.getMessage(), failure);
        } catch (IOException failure) {
            throw new GradleException("Cannot refresh backlog totals in " + index, failure);
        }
    }
}
