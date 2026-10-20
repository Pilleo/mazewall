plugins {
    `kotlin-dsl`
}

class TestKitJvmArguments(
    @get:org.gradle.api.tasks.Internal val gradleUserHome: java.io.File,
) : org.gradle.process.CommandLineArgumentProvider {
    @get:org.gradle.api.tasks.Input
    val cacheLayout = "caches/mazewall-test-kit"

    override fun asArguments() = listOf(
        "-Dorg.gradle.testkit.dir=${gradleUserHome.resolve(cacheLayout).absolutePath}",
        "-Dmazewall.test.gradleUserHome=${gradleUserHome.absolutePath}",
    )
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_22)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(22)
}

gradlePlugin {
    plugins {
        create("spotbugsToolchain") {
            id = "io.mazewall.spotbugs-toolchain"
            implementationClass = "io.mazewall.build.SpotBugsToolchainPlugin"
        }
        create("testHealth") {
            id = "io.mazewall.test-health"
            implementationClass = "io.mazewall.build.TestHealthPlugin"
        }
    }
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(libs.kotlinGradlePlugin)
    implementation(libs.detekt.gradle.plugin)
    implementation(libs.ktlint.gradle.plugin)
    implementation(libs.spotbugs.gradle.plugin)

    testImplementation(gradleTestKit())
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Executable Java fixtures must compile without the JAR required by Kotlin test compilation.
val verification = sourceSets.create("verification") {
    java.srcDir("src/test/java")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
kotlin.sourceSets.named("verification") { kotlin.srcDir("src/verification/kotlin") }
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileVerificationKotlin") {
    compilerOptions.freeCompilerArgs.addAll(
        providers.provider {
            listOf("-Xfriend-paths=" + sourceSets.main.get().output.classesDirs.files.joinToString(",") { it.absolutePath })
        },
    )
}
sourceSets.test.get().java.setSrcDirs(emptyList<String>())
configurations.named(verification.implementationConfigurationName) {
    extendsFrom(configurations.testImplementation.get())
}
configurations.named(verification.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.testRuntimeOnly.get())
}

val verificationActionsMarker = layout.buildDirectory.file("verification/verification-actions.success")
tasks.register<JavaExec>("buildLogicGateTest") {
    mainClass.set("io.mazewall.build.BuildLogicGateFunctionalTest")
    args(layout.projectDirectory.dir("..").asFile.absolutePath)
    inputs.file(layout.projectDirectory.file("settings.gradle.kts"))
        .withPropertyName("verificationSettings")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
tasks.register<JavaExec>("testKitCacheTest") {
    dependsOn(tasks.named(verification.classesTaskName))
    classpath = verification.runtimeClasspath
    mainClass.set("io.mazewall.build.TestKitCacheFunctionalTest")
}
tasks.register<JavaExec>("spotbugsReportTest") {
    dependsOn(tasks.named(verification.classesTaskName))
    classpath = verification.runtimeClasspath
    mainClass.set("io.mazewall.build.SpotBugsReportTest")
}
tasks.register<JavaExec>("spotbugsSourcePatchTest") {
    dependsOn(tasks.named(verification.classesTaskName))
    classpath = verification.runtimeClasspath
    mainClass.set("io.mazewall.build.SpotBugsSourcePatchTest")
}
tasks.register<JavaExec>("ciCachePolicyTest") {
    mainClass.set("io.mazewall.build.CiCacheKeyFunctionalTest")
    dependsOn(tasks.named(verification.classesTaskName))
    classpath = verification.runtimeClasspath
    args(layout.projectDirectory.dir("..").asFile.absolutePath)
}
tasks.register<JavaExec>("pitestLimitsTest") {
    mainClass.set("io.mazewall.build.PitestLimitsFunctionalTest")
    args(layout.projectDirectory.dir("..").asFile.absolutePath)
}

val verificationActionsTest = tasks.register<JavaExec>("verificationActionsTest") {
    val successMarker = verificationActionsMarker
    mainClass.set("io.mazewall.build.VerificationActionsTest")
    inputs.file(layout.projectDirectory.file("settings.gradle.kts"))
        .withPropertyName("verificationSettings")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(layout.projectDirectory.file("../scripts/ci_cache_key.sh"))
        .withPropertyName("containerCacheKeyHelper")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(layout.projectDirectory.file("../.github/workflows/ci.yml"))
        .withPropertyName("ciCachePolicyWorkflow")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(successMarker)
    args(successMarker.get().asFile.absolutePath)
    doFirst { successMarker.get().asFile.delete() }
}

tasks.withType<JavaExec>().matching { it.name in setOf("verificationActionsTest", "pitestLimitsTest") }.configureEach {
    inputs.file(layout.projectDirectory.file("../gradle/pitest-limits.gradle"))
        .withPropertyName("sharedPitestLimits")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(layout.projectDirectory.file("../gradle.properties"))
        .withPropertyName("rootGradleProperties")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

val failureTriageMarker = layout.buildDirectory.file("verification/failure-triage.success")
tasks.register<JavaExec>("failureTriageTest") {
    mainClass.set("io.mazewall.build.VerificationActionsTest")
    outputs.file(failureTriageMarker)
    args(failureTriageMarker.get().asFile.absolutePath, "--failure-triage")
    doFirst { failureTriageMarker.get().asFile.delete() }
}

tasks.withType<JavaExec>().matching { it.name in setOf("verificationActionsTest", "failureTriageTest", "testKitCacheTest", "pitestLimitsTest", "buildLogicGateTest") }.configureEach {
    dependsOn(tasks.named(verification.classesTaskName))
    classpath = verification.runtimeClasspath
    // Cache transport is not verification input: host/container homes intentionally differ.
    jvmArgumentProviders.add(TestKitJvmArguments(gradle.gradleUserHomeDir))
    doFirst {
        val properties = inputs.properties["systemProperties"] as Map<*, *>
        check("org.gradle.testkit.dir" !in properties && "mazewall.test.gradleUserHome" !in properties) {
            "TestKit cache locations must not invalidate verification when switching Gradle homes"
        }
        check(inputs.properties.keys.none { it.endsWith(".gradleUserHome") }) {
            "TestKit argument-provider home must remain an internal transport location"
        }
    }
    inputs.files(sourceSets.main.get().allSource, sourceSets.test.get().allSource, verification.allSource)
        .withPropertyName("verificationSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(layout.projectDirectory.file("build.gradle.kts"))
        .withPropertyName("verificationBuildLogic")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(layout.projectDirectory.file("../build.gradle.kts"))
        .withPropertyName("rootVerificationTaskWiring")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(layout.projectDirectory.file("../integrations/spring/mazewall-spring-boot/build.gradle.kts"))
        .withPropertyName("springPublicCompileClasspathWiring")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(layout.projectDirectory.file("../gradle/libs.versions.toml"))
        .withPropertyName("rootVerificationPluginCatalog")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(layout.projectDirectory.file("../config/spotbugs/exclude.xml"))
        .withPropertyName("rootSpotbugsExclusions")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(layout.projectDirectory.dir("../config/spotbugs/patches"))
        .withPropertyName("spotbugsToolPatches")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(layout.projectDirectory.dir("../profiler/src/main/kotlin/io/mazewall/profiler/triage"))
        .withPropertyName("productionTriageSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.test {
    useJUnitPlatform()
    dependsOn(verificationActionsTest)
    jvmArgumentProviders.add(TestKitJvmArguments(gradle.gradleUserHomeDir))
}

tasks.named("check") { dependsOn(verificationActionsTest) }
