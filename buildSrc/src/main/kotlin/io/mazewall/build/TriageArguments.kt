package io.mazewall.build

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.process.CommandLineArgumentProvider

abstract class TriageArguments : CommandLineArgumentProvider {
    @get:Input
    abstract val failedTaskPath: Property<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    override fun asArguments(): Iterable<String> =
        listOf(
            "--failed-task",
            failedTaskPath.get(),
            "--output",
            reportFile.get().asFile.absolutePath,
        )
}
