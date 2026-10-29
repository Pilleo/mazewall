import org.gradle.api.credentials.PasswordCredentials
import org.gradle.process.CommandLineArgumentProvider
import java.security.MessageDigest

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}
kotlin { jvmToolchain(25) }
val mazewallVersion = providers.gradleProperty("consumerVersion").get()
require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+[-.A-Za-z0-9]*").matches(mazewallVersion)) { "Pinned safe consumerVersion required" }
repositories {
    exclusiveContent {
        forRepository {
            maven {
                name = "mazewall"
                url = uri(providers.gradleProperty("consumerRepository").get())
                if (url.scheme == "https" && url.host == "maven.pkg.github.com") credentials(PasswordCredentials::class)
            }
        }
        filter { includeGroup("io.mazewall") }
    }
    mavenCentral()
}
dependencies {
    implementation("io.mazewall:koog:$mazewallVersion")
    implementation("ai.koog:agents-core:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
dependencyLocking {
    lockAllConfigurations()
    lockMode.set(LockMode.STRICT)
    lockFile.set(layout.projectDirectory.file("locks/$mazewallVersion/gradle.lockfile"))
}
application {
    mainClass.set("demo.koog.DocumentAgent")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
tasks.jar { archiveFileName.set("koog-document-agent.jar") }
val packagedClasspath = files(tasks.jar.flatMap { it.archiveFile }, configurations.runtimeClasspath)
abstract class PackagedClasspathArguments : CommandLineArgumentProvider {
    @get:Classpath abstract val jars: ConfigurableFileCollection
    override fun asArguments(): Iterable<String> = listOf("-Dexample.classpath=${jars.asPath}")
}
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxParallelForks = 1
    jvmArgumentProviders.add(objects.newInstance<PackagedClasspathArguments>().apply { jars.from(packagedClasspath) })
    testLogging { events("passed", "failed", "standardOut", "standardError"); showStandardStreams = true }
}
tasks.test { useJUnitPlatform { excludeTags("native") } }
tasks.register<Test>("nativeTest") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("native") }
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
tasks.register<JavaExec>("demoThread") {
    group = "application"
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    classpath = packagedClasspath
    mainClass.set("demo.koog.DocumentAgent")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
tasks.register("verifyPublishedClosure") {
    notCompatibleWithConfigurationCache("Fresh publication audit inspects the actual resolved dependency graph")
    val graph = configurations.runtimeClasspath
    inputs.files(graph)
    doLast {
        val rootId = graph.get().incoming.resolutionResult.rootComponent.get().id
        check(graph.get().incoming.resolutionResult.allComponents.none {
            it.id is org.gradle.api.artifacts.component.ProjectComponentIdentifier && it.id != rootId
        }) { "Published dependencies only; no sibling source substitution" }
        val artifacts = graph.get().resolvedConfiguration.resolvedArtifacts.filter { it.moduleVersion.id.group == "io.mazewall" }
        check(artifacts.isNotEmpty())
        artifacts.forEach {
            check(it.moduleVersion.id.version == mazewallVersion)
            val sha = MessageDigest.getInstance("SHA-256").digest(it.file.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
            println("PUBLISHED ${it.moduleVersion.id} sha256=$sha")
        }
    }
}
