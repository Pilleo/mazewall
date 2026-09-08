import dev.detekt.gradle.Detekt
import org.gradle.api.artifacts.VersionCatalogsExtension
import java.io.File

plugins {
    id("mazewall.test-conventions")
    id("dev.detekt")
    id("org.jlleitschuh.gradle.ktlint")
    id("com.github.spotbugs")
    jacoco
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

extensions.configure<JacocoPluginExtension> {
    toolVersion = libs.findVersion("jacoco").get().requiredVersion
}

configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    version.set(libs.findVersion("ktlintEngine").get().requiredVersion)
    verbose.set(true)
    outputToConsole.set(true)
    coloredOutput.set(true)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.HTML)
    }
}

tasks.withType<Detekt>().configureEach {
    jdkHome.set(layout.dir(providers.systemProperty("java.home").map(::File)))
}

spotbugs {
    ignoreFailures.set(false)
    showStackTraces.set(true)
    showProgress.set(false)
    effort.set(com.github.spotbugs.snom.Effort.DEFAULT)
    reportLevel.set(com.github.spotbugs.snom.Confidence.HIGH)
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    maxHeapSize.set("1g")
}

tasks.matching { it.name.startsWith("spotbugsTest") || it.name.startsWith("spotbugsIntegrationTest") }.configureEach {
    enabled = false
}

if (project != rootProject) {
    val unitTest = tasks.named<Test>("test")
    tasks.register("unitCheck") {
        group = "verification"
        description = "Runs host-safe unit tests and enforces unit-only coverage."
        dependsOn(unitTest, "jacocoTestCoverageVerification")
    }
    tasks.register("kernelCheck") {
        group = "verification"
        description = "Runs privileged kernel and fresh-JVM integration tests."
        dependsOn(tasks.matching { it.name == "integrationTest" || it.name == "integrationTestFreshJvm" })
        dependsOn("jacocoKernelTestReport", "jacocoKernelCoverageVerification")
    }
    tasks.named("check") {
        dependsOn("unitCheck")
    }
}
