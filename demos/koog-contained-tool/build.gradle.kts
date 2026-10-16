import io.mazewall.portal.PortalInstanceMode
import io.mazewall.portal.codegen.PortalCodegenExtension
import io.mazewall.portal.codegen.PortalCodegenPlugin
import io.mazewall.portal.codegen.PortalServiceRegistration
import java.io.File

buildscript {
    require(!gradle.startParameter.isConfigurationCacheRequested) {
        "Koog source bootstrap requires uncached receipt validation; use scripts/koog-build.sh"
    }
    val receiptFile = rootProject.layout.buildDirectory.file("portal-codegen/plugin-classpath.properties").get().asFile
    require(receiptFile.isFile) { "Build the actual Portal plugin first with scripts/koog-build.sh" }
    val receipt = java.util.Properties().apply { receiptFile.inputStream().use { load(it) } }
    fun digest(file: File): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    require(receipt.getProperty("format") == "1" && receipt.getProperty("root") == rootProject.projectDir.canonicalPath) {
        "Portal plugin receipt belongs to another format/worktree; rerun scripts/koog-build.sh"
    }
    require(receipt.getProperty("version") == rootProject.version.toString()) {
        "Stale Portal plugin producer version; rerun scripts/koog-build.sh with shared version options"
    }
    // Exact inventory detects source edits, additions, removals and root/catalog version changes.
    val sources = rootProject.fileTree(rootProject.projectDir) {
        include("*.gradle", "*.gradle.kts", "gradle.properties", "gradlew", "gradle/**")
        listOf("buildSrc", "platform", "enforcer", "profiler", "portals/runtime", "portals/worker", "portals/codegen").forEach {
            include("$it/src/**", "$it/*.gradle", "$it/*.gradle.kts")
        }
    }.files.associate { "source.${it.relativeTo(rootProject.projectDir).invariantSeparatorsPath}" to digest(it) }
    val recordedSources = receipt.stringPropertyNames().filter { it.startsWith("source.") }
        .associateWith { receipt.getProperty(it) }
    require(sources == recordedSources) { "Stale Portal plugin producer sources; rerun scripts/koog-build.sh" }
    val jars = receipt.getProperty("classpath")?.split(File.pathSeparator)?.map { File(it) }
        ?: error("Missing Portal plugin classpath; rerun scripts/koog-build.sh")
    require(jars.isNotEmpty() && jars.all { it.isFile && receipt.getProperty("artifact.${it.canonicalPath}") == digest(it) }) {
        "Stale/missing Portal plugin artifact; rerun scripts/koog-build.sh"
    }
    logger.lifecycle("Using actual Portal plugin producer version={} revision={}", receipt.getProperty("version"), receipt.getProperty("revision"))
    dependencies { classpath(files(jars)) }
}

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    `java-library`
    `maven-publish`
}
apply<PortalCodegenPlugin>()
kotlin { jvmToolchain(25) }
java { withSourcesJar() }
val guest = sourceSets.create("portalWorker")
val nativeFixtures = providers.gradleProperty("koogNativeFixtures").orNull == "true"

dependencies {
    api(project(":integrations:koog"))
    api(project(":demos:koog-contained-tool:service-api"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    add(guest.implementationConfigurationName, project(":demos:koog-contained-tool:service-api"))
    add(guest.implementationConfigurationName, project(":portals:worker"))
    if (nativeFixtures) {
        implementation(project(":koog-denied-api"))
        add(guest.implementationConfigurationName, project(":koog-denied-api"))
    }
    testImplementation(kotlin("test"))
    testImplementation(rootProject.sourceSets["sharedTest"].output)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
if (nativeFixtures) {
    kotlin.sourceSets.getByName("portalWorker").kotlin.srcDir(rootProject.file("tests/consumers/koog-portal/denied-worker"))
}
configure<PortalCodegenExtension> {
    workerSourceSet.set("portalWorker")
    registrations.set(listOf(PortalServiceRegistration(
        "bounded-text", "io.mazewall.demo.koog.TextTransformerService",
        "io.mazewall.demo.koog.worker.AsciiTextTransformer", "bounded-text",
        PortalInstanceMode.STATEFUL_SINGLETON, "static-baseline", "bounded-text-worker",
    )))
    if (nativeFixtures) registrations.add(PortalServiceRegistration(
        "test-denied", "io.mazewall.consumer.koog.DeniedResourceService",
        "io.mazewall.consumer.koog.worker.DeniedResourceProbe", "test-denied",
        PortalInstanceMode.STATEFUL_SINGLETON, "static-baseline", "bounded-text-worker",
    ))
}
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    dependsOn(tasks.named("jar"))
}
publishing {
    publications { create<MavenPublication>("containedTool") { from(components["java"]) } }
    repositories { maven { name = "consumerStage"; url = uri(layout.buildDirectory.dir("consumer-stage")) } }
}
