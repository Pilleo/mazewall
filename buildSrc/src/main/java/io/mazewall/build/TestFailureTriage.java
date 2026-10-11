package io.mazewall.build;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;
import org.gradle.build.event.BuildEventsListenerRegistry;
import org.gradle.tooling.events.FinishEvent;
import org.gradle.tooling.events.OperationCompletionListener;
import org.gradle.tooling.events.task.TaskFailureResult;
import org.gradle.tooling.events.task.TaskFinishEvent;

/** Configuration-cache-safe observation of failed Test tasks in the current build. */
public abstract class TestFailureTriage {
    @Inject
    protected abstract BuildEventsListenerRegistry getEvents();

    public void wire(Project root, TaskProvider<? extends Task> triage) {
        var barrier = root.getTasks().register("testFailureEventsReady");
        var failures = root.getGradle().getSharedServices().registerIfAbsent(
            "mazewallTestFailures", FailureEvents.class, spec -> {
                spec.getParameters().getBarrierTaskPath().set(barrier.get().getPath());
                spec.getParameters().getTestTaskPaths().set(root.provider(() -> root.getSubprojects().stream()
                    .flatMap(project -> project.getTasks().withType(Test.class).stream())
                    .map(Task::getPath).toList()));
            });
        getEvents().onTaskCompletion(failures);
        root.subprojects(project -> project.getTasks().withType(Test.class).configureEach(test -> {
            test.finalizedBy(triage);
            barrier.configure(task -> task.mustRunAfter(test));
        }));
        triage.configure(task -> {
            task.dependsOn(barrier);
            task.usesService(failures);
            task.onlyIf("a Test task failed in this build", ignored -> failures.get().awaitFailure());
        });
    }

    public interface Parameters extends BuildServiceParameters {
        ListProperty<String> getTestTaskPaths();
        Property<String> getBarrierTaskPath();
    }

    public abstract static class FailureEvents implements BuildService<Parameters>, OperationCompletionListener {
        private final CountDownLatch delivered = new CountDownLatch(1);
        private boolean failed;

        @Override
        public void onFinish(FinishEvent event) {
            if (event instanceof TaskFinishEvent task) {
                String path = task.getDescriptor().getTaskPath();
                if (task.getResult() instanceof TaskFailureResult && getParameters().getTestTaskPaths().get().contains(path)) {
                    failed = true;
                }
                // Gradle delivers task events serially in completion order; this drains preceding Tests.
                if (path.equals(getParameters().getBarrierTaskPath().get())) delivered.countDown();
            }
        }

        public boolean awaitFailure() {
            return awaitFailure(60_000);
        }

        boolean awaitFailure(long timeoutMillis) {
            try {
                if (!delivered.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    throw new GradleException("Timed out awaiting Test failure completion events");
                }
                return failed;
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new GradleException("Interrupted awaiting Test failure completion events", failure);
            }
        }
    }
}
