package io.mazewall.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class VerifyDependencyState : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val verificationMetadata: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val lockfiles: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        check(verificationMetadata.asFile.get().isFile) { "Missing Gradle checksum metadata" }
        val missing = lockfiles.files.filterNot { it.isFile }
        check(missing.isEmpty()) { "Missing dependency locks: ${missing.joinToString { it.path }}" }
    }
}
