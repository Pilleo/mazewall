package io.mazewall.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Path

/** Public task type is required by the root Gradle script's separate Kotlin module. */
@DisableCachingByDefault(because = "Inventory observes current Git index, aliases and source permissions; no reusable output")
abstract class CheckGradleLazyResolution : DefaultTask() {
    @get:Input
    abstract val checkoutPath: Property<String>

    @TaskAction
    fun verify() {
        val violations = GradleSourceInventory(Path.of(checkoutPath.get())).scan()
        if (violations.isNotEmpty()) throw GradleException(violations.joinToString("\n"))
    }
}
