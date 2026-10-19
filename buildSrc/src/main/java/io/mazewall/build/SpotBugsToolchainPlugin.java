package io.mazewall.build;

import com.github.spotbugs.snom.SpotBugsExtension;
import com.github.spotbugs.snom.SpotBugsTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.VersionCatalogsExtension;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.bundling.Jar;

/** Unpublished analyzer-only replacements; application classpaths are never modified. */
public final class SpotBugsToolchainPlugin implements Plugin<Project> {
    @Override public void apply(Project root) {
        if (root != root.getRootProject()) throw new IllegalArgumentException("Analyzer producer must belong to the root project");
        var catalog = root.getExtensions().getByType(VersionCatalogsExtension.class).named("libs");
        String version = catalog.findVersion("spotbugsCore").orElseThrow().getRequiredVersion();
        var sources = root.getConfigurations().create("spotbugsPatchedSources", c -> {
            c.setCanBeConsumed(false);
            c.setTransitive(false);
            c.getResolutionStrategy().activateDependencyLocking();
        });
        var compiler = root.getConfigurations().create("spotbugsPatchCompiler", c -> {
            c.setCanBeConsumed(false);
            c.getResolutionStrategy().activateDependencyLocking();
        });
        root.getDependencies().add(sources.getName(), "com.github.spotbugs:spotbugs:" + version + ":sources@jar");
        root.getDependencies().add(compiler.getName(), "com.github.spotbugs:spotbugs:" + version);
        var generated = root.getTasks().register("generateSpotBugsSources", GenerateSpotBugsSources.class, task -> {
            task.getUpstreamArchive().from(sources);
            task.getPatches().from(root.fileTree("config/spotbugs/patches", tree -> tree.include("*.properties")));
            task.getOutputDirectory().set(root.getLayout().getBuildDirectory().dir("generated/spotbugs-patches"));
        });
        var compiled = root.getTasks().register("compileSpotBugsPatches", JavaCompile.class, task -> {
            task.source(generated.flatMap(GenerateSpotBugsSources::getOutputDirectory));
            task.setClasspath(compiler);
            task.getDestinationDirectory().set(root.getLayout().getBuildDirectory().dir("classes/spotbugs-patches"));
            task.getOptions().getRelease().set(17);
            task.getOptions().setEncoding("UTF-8");
        });
        var overlay = root.getTasks().register("spotbugsPatchJar", Jar.class, task -> {
            task.getArchiveBaseName().set("mazewall-spotbugs-tool-only");
            task.getArchiveFileName().set("mazewall-spotbugs-tool-only.jar");
            task.getDestinationDirectory().set(root.getLayout().getBuildDirectory().dir("tools/spotbugs"));
            task.setPreserveFileTimestamps(false);
            task.setReproducibleFileOrder(true);
            task.from(compiled.flatMap(JavaCompile::getDestinationDirectory));
            task.from(generated.flatMap(GenerateSpotBugsSources::getOutputDirectory), spec -> spec.into("META-INF/sources"));
            task.from(root.file("config/spotbugs/patches"), spec -> spec.into("META-INF/spotbugs-patches"));
            task.from(root.zipTree(root.provider(() -> compiler.getFiles().stream()
                    .filter(file -> file.getName().equals("spotbugs-" + version + ".jar")).findFirst().orElseThrow())), spec -> {
                spec.include("edu/umd/cs/findbugs/gui2/help/License.html");
                spec.eachFile(file -> file.setPath("META-INF/spotbugs-LICENSE.html"));
                spec.setIncludeEmptyDirs(false);
            });
        });
        root.allprojects(project -> project.getPlugins().withId("com.github.spotbugs", ignored -> {
            project.getExtensions().getByType(SpotBugsExtension.class).getToolVersion().set(version);
            project.getConfigurations().named("spotbugsSlf4j", c -> c.getResolutionStrategy().activateDependencyLocking());
            project.getTasks().withType(SpotBugsTask.class).configureEach(task -> {
                task.getSpotbugsClasspath().setFrom(overlay.flatMap(Jar::getArchiveFile),
                        project.getConfigurations().named("spotbugs"), project.getConfigurations().named("spotbugsSlf4j"));
                var report = task.getReports().maybeCreate("xml");
                report.getRequired().set(true);
                task.doLast(new VerifySpotBugsReport(report.getOutputLocation().get().getAsFile()));
            });
        }));
    }
}
