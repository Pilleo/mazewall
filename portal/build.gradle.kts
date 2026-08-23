plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(25)
}

sourceSets {
    create("integrationTest") {
        compileClasspath += main.get().output + test.get().output
        runtimeClasspath += main.get().output + test.get().output
    }
}

val kotlinExtension = extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>()
val kotlinCompilations = kotlinExtension.target.compilations
kotlinCompilations.named("integrationTest") {
    associateWith(kotlinCompilations.getByName("main"))
    associateWith(kotlinCompilations.getByName("test"))
}

evaluationDependsOn(":portal-worker")

/**
 * Full runtime classpath for spawned portal workers (issue: worker JVM must load
 * PortalWorkerMain + :portal/:platform/:enforcer). Referencing the FileCollection directly
 * gives tasks automatic build dependencies on the producer classes; converting it to a String
 * inside a systemProperty MUST stay lazy (providers.provider) — eager resolution at
 * configuration time is forbidden by Gradle 9 and produced empty paths (CNFE in workers).
 */
val portalWorkerClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isVisible = false
}

// Plain FileCollection view so task doFirst blocks don't capture the script object
// (configuration-cache requirement).
val portalWorkerCpFiles: FileCollection = portalWorkerClasspath

/**
 * Carries the worker classpath as a JVM arg; @Classpath gives implicit producer deps and
 * CC-compatible fingerprinting. Resolution happens at execution time.
 */
abstract class PortalWorkerClasspathArgProvider @javax.inject.Inject constructor(
    @get:Classpath private val workerCp: FileCollection,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("-Dio.mazewall.portal.worker.classpath=" + workerCp.asPath)
}

val portalWorkerCpArgProvider =
    objects.newInstance(PortalWorkerClasspathArgProvider::class.java, portalWorkerCpFiles)

val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}

val integrationTestRuntimeOnly by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}

val integrationTest =
    tasks.register<Test>("integrationTest") {
        group = "verification"
        description = "Process-portal kernel tests (spawn worker JVM)"
        testClassesDirs = sourceSets["integrationTest"].output.classesDirs
        classpath = sourceSets["integrationTest"].runtimeClasspath
        useJUnitPlatform()
        jvmArgs(
            "--enable-native-access=ALL-UNNAMED",
            "-Xmx256m",
            "-Dfile.encoding=UTF-8",
        )
        systemProperty("kotest.framework.classpath.scanning.config.disable", "true")
        forkEvery = 1
        maxParallelForks = 1
        // Worker classpath resolution happens at EXECUTION time (Gradle 9 forbids eager
        // configuration-time resolution), so the producer must be built explicitly or the
        // resolved path is missing worker classes -> ClassNotFoundException in the worker JVM.
        dependsOn(":portal-worker:classes")
        jvmArgumentProviders.add(portalWorkerCpArgProvider)
    }

tasks.check {
    dependsOn(integrationTest)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx256m")
    systemProperty("kotest.framework.classpath.scanning.config.disable", "true")
    dependsOn(":portal-worker:classes")
    jvmArgumentProviders.add(portalWorkerCpArgProvider)
}

dependencies {
    api(project(":platform"))
    implementation(project(":enforcer"))
    portalWorkerClasspath(project(":portal-worker"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.slf4j.nop)
    integrationTestImplementation(kotlin("test"))
    integrationTestImplementation(libs.junit.jupiter.api)
    integrationTestRuntimeOnly(libs.junit.jupiter.engine)
}

