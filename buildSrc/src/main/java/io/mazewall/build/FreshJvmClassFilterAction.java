package io.mazewall.build;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.tasks.testing.Test;

/**
 * Limits a fresh-JVM test task to classes containing the {@code NeedsFreshJvm}
 * annotation. A compiled action avoids capturing a Kotlin build-script object,
 * which Gradle's configuration cache cannot serialize.
 */
public final class FreshJvmClassFilterAction implements Action<Task> {
    @Override
    public void execute(Task task) {
        Test test = (Test) task;
        test.getFilter().setFailOnNoMatchingTests(false);
        for (String className : FreshJvmClassSelector.select(test.getTestClassesDirs().getFiles())) {
            test.getFilter().includeTestsMatching(className);
        }
    }
}
