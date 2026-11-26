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
    friendPaths.from(sourceSets.main.get().output.classesDirs)
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
tasks.register<JavaExec>("testHealthCacheTest") {
    mainClass.set("io.mazewall.build.TestHealthPluginFunctionalTest")
}
tasks.register<JavaExec>("friendPathCacheTest") {
    mainClass.set("io.mazewall.build.FriendPathCacheFunctionalTest")
    args(layout.projectDirectory.dir("..").asFile.absolutePath)
}

tasks.register<JavaExec>("confinedHelperLaneTest") {
    mainClass.set("io.mazewall.build.ConfinedHelperLaneFunctionalTest")
    systemProperty("mazewall.test.outerOffline", gradle.startParameter.isOffline.toString())
    inputs.file(layout.projectDirectory.file("../gradle/verification-metadata.xml"))
        .withPropertyName("fixtureDependencyVerificationMetadata")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(layout.projectDirectory.file("../gradle/libs.versions.toml"))
        .withPropertyName("fixtureVersionCatalog")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    args(layout.projectDirectory.dir("..").asFile.absolutePath)
}
tasks.register<JavaExec>("btfRelocationTest") {
    mainClass.set("io.mazewall.build.BtfRelocationFunctionalTest")
    args(layout.projectDirectory.dir("..").asFile.absolutePath)
}
tasks.register<JavaExec>("nativeDiscoveryTest") {
    mainClass.set("io.mazewall.build.NativeTestDiscoveryFunctionalTest")
    args(layout.projectDirectory.dir("..").asFile.absolutePath)
}
tasks.register<JavaExec>("coreLaneTest") {
    mainClass.set("io.mazewall.build.CoreLaneFunctionalTest")
    args(layout.projectDirectory.dir("..").asFile.absolutePath)
}
tasks.register<JavaExec>("spotbugsReportTest") {
    dependsOn(tasks.named(verification.classesTaskName))
    classpath = verification.runtimeClasspath
    mainClass.set("io.mazewall.build.SpotBugsReportTest")
}
tasks.register<JavaExec>("spotbugsAuxClasspathTest") {
    dependsOn(tasks.named(verification.classesTaskName))
    classpath = verification.runtimeClasspath
    mainClass.set("io.mazewall.build.SpotBugsAuxClasspathFunctionalTest")
    args(layout.projectDirectory.dir("..").asFile.absolutePath)
    jvmArgumentProviders.add(TestKitJvmArguments(gradle.gradleUserHomeDir))
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
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
    environment("LANG", "C.UTF-8")
    environment("LC_ALL", "C.UTF-8")
    args(layout.projectDirectory.dir("..").asFile.absolutePath)
    inputs.files(
        layout.projectDirectory.file("../.github/workflows/ci.yml"),
        layout.projectDirectory.file("../.github/workflows/security-data.yml"),
        layout.projectDirectory.file("../.github/ci-code-paths.yml"),
        layout.projectDirectory.file("../scripts/ci_required_gate.py"),
        layout.projectDirectory.file("../scripts/test_ci_required_gate.py"),
        layout.projectDirectory.file("../scripts/security_snapshot.py"),
        layout.projectDirectory.file("../scripts/test_security_snapshot.py"),
    ).withPropertyName("securitySnapshotPolicyInputs").withPathSensitivity(PathSensitivity.RELATIVE)
}
tasks.register<JavaExec>("securitySnapshotVerificationTest") {
    dependsOn(tasks.named(verification.classesTaskName))
    classpath = verification.runtimeClasspath
    mainClass.set("io.mazewall.build.SecuritySnapshotVerificationFunctionalTest")
    jvmArgumentProviders.add(TestKitJvmArguments(gradle.gradleUserHomeDir))
}

tasks.register<JavaExec>("pitestLimitsTest") {
    mainClass.set("io.mazewall.build.PitestLimitsFunctionalTest")
    args(layout.projectDirectory.dir("..").asFile.absolutePath)
}

val verificationActionsTest = tasks.register<JavaExec>("verificationActionsTest") {
    val successMarker = verificationActionsMarker
    systemProperty("mazewall.test.outerOffline", gradle.startParameter.isOffline.toString())
    mainClass.set("io.mazewall.build.VerificationActionsTest")
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
    environment("LANG", "C.UTF-8")
    environment("LC_ALL", "C.UTF-8")
    inputs.file(layout.projectDirectory.file("settings.gradle.kts"))
        .withPropertyName("verificationSettings")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(layout.projectDirectory.file("../gradle/verification-metadata.xml"))
        .withPropertyName("fixtureDependencyVerificationMetadata")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(layout.projectDirectory.file("../scripts/ci_cache_key.sh"))
        .withPropertyName("containerCacheKeyHelper")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(layout.projectDirectory.file("../.github/workflows/ci.yml"))
        .withPropertyName("ciCachePolicyWorkflow")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        layout.projectDirectory.file("../.github/workflows/security-data.yml"),
        layout.projectDirectory.file("../.github/ci-code-paths.yml"),
        layout.projectDirectory.file("../scripts/ci_required_gate.py"),
        layout.projectDirectory.file("../scripts/test_ci_required_gate.py"),
        layout.projectDirectory.file("../scripts/security_snapshot.py"),
        layout.projectDirectory.file("../scripts/test_security_snapshot.py"),
    ).withPropertyName("securitySnapshotPolicyInputs").withPathSensitivity(PathSensitivity.RELATIVE)
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
    systemProperty("mazewall.test.outerOffline", gradle.startParameter.isOffline.toString())
    inputs.property("outerOffline", gradle.startParameter.isOffline)
    inputs.file(layout.projectDirectory.file("../gradle/verification-metadata.xml"))
        .withPropertyName("fixtureDependencyVerificationMetadata")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(failureTriageMarker)
    args(failureTriageMarker.get().asFile.absolutePath, "--failure-triage")
    doFirst { failureTriageMarker.get().asFile.delete() }
}

tasks.withType<JavaExec>().matching { it.name in setOf("verificationActionsTest", "failureTriageTest", "testKitCacheTest", "testHealthCacheTest", "friendPathCacheTest", "confinedHelperLaneTest", "btfRelocationTest", "nativeDiscoveryTest", "coreLaneTest", "pitestLimitsTest", "buildLogicGateTest") }.configureEach {
    dependsOn(tasks.named(verification.classesTaskName))
    classpath = verification.runtimeClasspath
    if (name == "verificationActionsTest") {
        val metadata = tasks.named("pluginUnderTestMetadata")
        dependsOn(metadata)
        classpath += files(metadata)
    }
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

tasks.withType<JavaExec>().matching { it.name in setOf("verificationActionsTest", "friendPathCacheTest", "confinedHelperLaneTest") }.configureEach {
    inputs.file(layout.projectDirectory.file("../enforcer/build.gradle.kts"))
        .withPropertyName("friendPathConvention")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.withType<JavaExec>().matching { it.name in setOf("verificationActionsTest", "btfRelocationTest") }.configureEach {
    inputs.files(
        layout.projectDirectory.file("../profiler/build.gradle.kts"),
        layout.projectDirectory.file("../profiler/src/main/c/btf/invocation_state.c"),
    ).withPropertyName("btfResourceProducer").withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.withType<JavaExec>().matching { it.name in setOf("verificationActionsTest", "nativeDiscoveryTest") }.configureEach {
    inputs.file(layout.projectDirectory.file("../portals/runtime/build.gradle.kts"))
        .withPropertyName("nativeTestDiscoveryWiring").withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.named<JavaExec>("coreLaneTest") {
    inputs.file(layout.projectDirectory.file("../.github/workflows/ci.yml"))
        .withPropertyName("coreLaneWorkflow").withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.test {
    useJUnitPlatform()
    dependsOn(verificationActionsTest)
    jvmArgumentProviders.add(TestKitJvmArguments(gradle.gradleUserHomeDir))
}

tasks.named("check") { dependsOn(verificationActionsTest) }
