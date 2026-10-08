import io.mazewall.portal.PortalInstanceMode
import io.mazewall.portal.codegen.PortalCodegenExtension
import io.mazewall.portal.codegen.PortalCodegenPlugin
import io.mazewall.portal.codegen.PortalServiceRegistration

buildscript {
    // Scoped source-verification fixture supplies the actual built plugin classpath.
    // Production plugin resolution is a coordinator-owned root wiring request.
    val classpathFile = rootProject.layout.buildDirectory.file("task7-codegen-classpath.txt").get().asFile
    require(classpathFile.isFile) { "Run :portals:codegen:task7CodegenClasspath using the scoped init fixture first" }
    dependencies { classpath(files(classpathFile.readText().split(java.io.File.pathSeparator))) }
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
tasks.test { useJUnitPlatform() }
publishing {
    publications { create<MavenPublication>("containedTool") { from(components["java"]) } }
    repositories { maven { name = "consumerStage"; url = uri(layout.buildDirectory.dir("consumer-stage")) } }
}
