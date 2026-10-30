import io.mazewall.portal.PortalInstanceMode
import io.mazewall.portal.codegen.PortalCodegenExtension
import io.mazewall.portal.codegen.PortalServiceRegistration
import org.gradle.api.credentials.PasswordCredentials
import org.gradle.process.CommandLineArgumentProvider
import java.security.MessageDigest

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("io.mazewall.portal.codegen")
}
kotlin { jvmToolchain(25) }
val apiSource = sourceSets.create("api")
val workerSource = sourceSets.create("portalWorker")
val apiJar =
    tasks.register<Jar>("apiJar") {
        from(apiSource.output)
        archiveFileName.set("document-api.jar")
    }
sourceSets.test {
    compileClasspath += workerSource.output
    runtimeClasspath += workerSource.output
}
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
    implementation(files(apiJar))
    implementation("io.mazewall:koog:$mazewallVersion")
    implementation("ai.koog:agents-core:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.mazewall:profiler:$mazewallVersion")
    add(workerSource.implementationConfigurationName, files(apiJar))
    add(workerSource.implementationConfigurationName, "io.mazewall:worker:$mazewallVersion")
}
configure<PortalCodegenExtension> {
    workerSourceSet.set("portalWorker")
    registrations.set(
        listOf(
            PortalServiceRegistration(
                "document-outline",
                "demo.documents.DocumentOutlineService",
                "demo.documents.MarkdownOutline",
                "document-outline",
                PortalInstanceMode.STATEFUL_SINGLETON,
                "static-baseline",
                "document-outline-worker",
            ),
        ),
    )
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
tasks.distZip { archiveFileName.set("koog-document-agent.zip") }
tasks.register<Zip>("sourceZip") {
    archiveFileName.set("koog-document-agent-sources.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.projectDirectory) {
        include(
            "README.md",
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
            "gradlew",
            "gradlew.bat",
            "gradle/wrapper/**",
            "gradle/verification-metadata.xml",
            "locks/**",
            "src/**",
            "boundary-fixture/build.gradle.kts",
            "boundary-fixture/locks/**",
            "boundary-fixture/src/**",
        )
        filesMatching("gradlew") { permissions { unix("rwxr-xr-x") } }
    }
}
val packagedClasspath = files(tasks.jar.flatMap { it.archiveFile }, configurations.runtimeClasspath)
val boundaryApiJar = files(project(":boundary-fixture").layout.buildDirectory.file("libs/boundary-api.jar")).builtBy(":boundary-fixture:apiJar")
val boundaryHostJar = files(project(":boundary-fixture").layout.buildDirectory.file("libs/boundary-fixture.jar")).builtBy(":boundary-fixture:jar")
dependencies {
    testImplementation(files(boundaryApiJar))
    testRuntimeOnly(files(boundaryHostJar))
}
val nativeProbeJar =
    tasks.register<Jar>("nativeProbeJar") {
        from(sourceSets.test.get().output)
        archiveFileName.set("document-native-probes.jar")
    }
val boundaryClasspath = packagedClasspath + files(nativeProbeJar, boundaryApiJar, boundaryHostJar)

abstract class PackagedClasspathArguments : CommandLineArgumentProvider {
    @get:Classpath abstract val jars: ConfigurableFileCollection

    @get:Classpath abstract val boundaryJars: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> = listOf("-Dexample.classpath=${jars.asPath}", "-Dexample.boundary.classpath=${boundaryJars.asPath}")
}
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxParallelForks = 1
    jvmArgumentProviders.add(
        objects.newInstance<PackagedClasspathArguments>().apply {
            jars.from(packagedClasspath)
            boundaryJars.from(boundaryClasspath)
        },
    )
    testLogging {
        events("passed", "failed", "standardOut", "standardError")
        showStandardStreams = true
    }
}
tasks.test { useJUnitPlatform { excludeTags("native") } }
tasks.register<Test>("nativeTest") {
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
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
tasks.register<JavaExec>("triageIncident") {
    group = "application"
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    classpath = packagedClasspath
    mainClass.set("demo.koog.IncidentApplication")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    val bundle = providers.gradleProperty("incidentBundle")
    val provider = providers.gradleProperty("incidentProvider")
    val model = providers.gradleProperty("incidentModel")
    argumentProviders.add(CommandLineArgumentProvider { listOf(bundle.get(), provider.get(), model.get()) })
}
tasks.register<JavaExec>("demoPortal") {
    group = "application"
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    classpath = boundaryClasspath
    mainClass.set("demo.koog.PortalBoundaryProbe")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
tasks.register<JavaExec>("runPortal") {
    group = "application"
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    classpath = packagedClasspath
    mainClass.set("demo.koog.PortalDocumentAgent")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
tasks.register<JavaExec>("outlineFile") {
    group = "application"
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    classpath = packagedClasspath
    mainClass.set("demo.koog.OutlineFileApplication")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    val input = providers.gradleProperty("outlineDocument")
    argumentProviders.add(CommandLineArgumentProvider { listOf(input.get()) })
}
tasks.register<JavaExec>("profileRead") {
    group = "application"
    description = "Development-only measured named reader workload; incomplete coverage refuses policy conversion."
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    classpath = packagedClasspath + files(nativeProbeJar) + configurations.testRuntimeClasspath.get()
    mainClass.set("demo.koog.DocumentProfile")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    val document = providers.gradleProperty("profileDocument")
    argumentProviders.add(CommandLineArgumentProvider { listOf(document.get()) })
}
tasks.register<JavaExec>("enforceReviewedRead") {
    group = "application"
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    classpath = packagedClasspath + files(nativeProbeJar) + configurations.testRuntimeClasspath.get()
    mainClass.set("demo.koog.ReviewedDocumentRead")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    val document = providers.gradleProperty("profileDocument")
    val candidate = providers.gradleProperty("operationDocument")
    val review = providers.gradleProperty("trustedReview")
    argumentProviders.add(CommandLineArgumentProvider { listOf(document.get(), candidate.get(), review.get()) })
}
for (mode in listOf("reader-direct", "reader-thread", "outline-direct", "outline-portal")) {
    tasks.register<JavaExec>("measure-" + mode) {
        group = "application"
        javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
        classpath = packagedClasspath + files(nativeProbeJar)
        if (mode == "outline-direct") classpath += workerSource.output
        mainClass.set("demo.koog.ApplicationMeasurements")
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        args(mode)
    }
}
tasks.register("verifyPublishedClosure") {
    notCompatibleWithConfigurationCache("Fresh publication audit inspects the actual resolved dependency graph")
    val graph = configurations.runtimeClasspath
    inputs.files(graph)
    doLast {
        val rootId =
            graph
                .get()
                .incoming.resolutionResult.rootComponent
                .get()
                .id
        check(
            graph.get().incoming.resolutionResult.allComponents.none {
                it.id is org.gradle.api.artifacts.component.ProjectComponentIdentifier && it.id != rootId
            },
        ) { "Published dependencies only; no sibling source substitution" }
        val artifacts =
            graph
                .get()
                .resolvedConfiguration.resolvedArtifacts
                .filter { it.moduleVersion.id.group == "io.mazewall" }
        check(artifacts.isNotEmpty())
        artifacts.forEach {
            check(it.moduleVersion.id.version == mazewallVersion)
            val sha = MessageDigest.getInstance("SHA-256").digest(it.file.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
            println("PUBLISHED ${it.moduleVersion.id} sha256=$sha")
        }
    }
}
