package io.mazewall.build;

import java.util.List;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.Delete;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;

/** Root convention that records fresh test forks without changing which tests a gate selects. */
public final class TestHealthPlugin implements Plugin<Project> {
    private static final String FRAGMENT_DIRECTORY = "reports/test-health/fragments";
    private static final String AGGREGATE_FILE = "reports/test-health/test-tier-health.json";

    @Override
    public void apply(Project root) {
        if (root != root.getRootProject()) {
            throw new IllegalArgumentException("io.mazewall.test-health must be applied to the root project");
        }
        var fragments = root.getLayout().getBuildDirectory().dir(FRAGMENT_DIRECTORY);
        var output = root.getLayout().getBuildDirectory().file(AGGREGATE_FILE);
        var invocation = root.getGradle().getSharedServices().registerIfAbsent(
            "mazewallTestHealthInvocation",
            TestHealthInvocation.class,
            ignored -> {}
        );
        TaskProvider<Delete> prepare = root.getTasks().register("prepareTestHealth", Delete.class, task -> {
            task.setDescription("Clears test-health fragments so cached tasks cannot masquerade as fresh execution");
            task.delete(fragments);
        });
        TaskProvider<Task> aggregate = root.getTasks().register("aggregateTestHealth", task -> {
            task.setGroup("verification");
            task.setDescription("Deterministically aggregates fresh per-module/task/fork test-health evidence");
            task.getOutputs().file(output);
            task.getOutputs().upToDateWhen(ignored -> false);
        });
        Task aggregateTask = aggregate.get();

        root.allprojects(project -> project.getTasks().withType(Test.class).configureEach(test -> {
            test.dependsOn(prepare);
            test.systemProperty("io.mazewall.testHealth.fragmentDir", fragments.get().getAsFile().getAbsolutePath());
            test.systemProperty("io.mazewall.testHealth.module", project.getPath().isBlank() ? ":" : project.getPath());
            test.systemProperty("io.mazewall.testHealth.task", test.getName());
            test.systemProperty("io.mazewall.testHealth.taskPath", test.getPath());
            test.usesService(invocation);
            test.doFirst(ignored -> test.systemProperty(
                "io.mazewall.testHealth.invocationToken",
                invocation.get().token()
            ));
            // This imposes ordering only when a producer is already in the chosen gate.
            // It deliberately does not make unitCheck execute kernel/integration tests.
            aggregateTask.mustRunAfter(test);
        }));

        root.getTasks().matching(task -> List.of("unitCheck", "kernelCheck", "check").contains(task.getName()))
            .configureEach(task -> task.finalizedBy(aggregate));

        root.getGradle().projectsEvaluated(gradle -> {
            List<String> expectedTasks = root.getAllprojects().stream()
                .flatMap(project -> project.getTasks().withType(Test.class).stream())
                .map(Task::getPath)
                .sorted()
                .toList();
            aggregate.configure(task -> {
                task.usesService(invocation);
                task.getInputs().property("expectedTestTasks", expectedTasks);
                task.doLast(new TestHealthAggregateAction(
                    fragments.get().getAsFile(),
                    output.get().getAsFile(),
                    expectedTasks,
                    invocation
                ));
            });
        });
    }
}
