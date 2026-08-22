package io.mazewall.portal.codegen

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.net.URLClassLoader

public abstract class PortalCodegenExtension {
    public abstract val interfaces: ListProperty<String>
}

@CacheableTask
public abstract class GeneratePortalStubsTask : DefaultTask() {
    @get:Input
    public abstract val interfaceNames: ListProperty<String>

    @get:Classpath
    public abstract val classpath: ConfigurableFileCollection

    @get:OutputDirectory
    public abstract val outputDir: DirectoryProperty

    @TaskAction
    public fun generate() {
        val out = outputDir.get().asFile
        out.mkdirs()
        val urls = classpath.files.map { it.toURI().toURL() }.toTypedArray()
        URLClassLoader(urls, PortalStubGenerator::class.java.classLoader).use { cl ->
            for (name in interfaceNames.get()) {
                val type = Class.forName(name, false, cl)
                PortalStubGenerator.write(type, out)
            }
        }
    }
}

public class PortalCodegenPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("portalCodegen", PortalCodegenExtension::class.java)
        ext.interfaces.convention(emptyList())
        val generate =
            project.tasks.register("generatePortalStubs", GeneratePortalStubsTask::class.java) { task ->
                task.group = "codegen"
                task.description = "Generate process-portal host stubs and worker dispatchers"
                task.interfaceNames.set(ext.interfaces)
                task.outputDir.set(project.layout.buildDirectory.dir("generated/portal"))
                val compileCp = project.configurations.findByName("compileClasspath")
                if (compileCp != null) {
                    task.classpath.from(compileCp)
                }
            }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            val kotlinExt =
                project.extensions.getByType(org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension::class.java)
            kotlinExt.sourceSets.getByName("main").kotlin.srcDir(generate.map { it.outputDir })
            project.tasks.named("compileKotlin").configure { it.dependsOn(generate) }
        }
    }
}
