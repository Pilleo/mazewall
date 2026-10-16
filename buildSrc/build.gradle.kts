plugins {
    `kotlin-dsl`
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
val verificationActionsTest = tasks.register<JavaExec>("verificationActionsTest") {
    mainClass.set("io.mazewall.build.VerificationActionsTest")
    outputs.file(verificationActionsMarker)
    args(verificationActionsMarker.get().asFile.absolutePath)
    doFirst { verificationActionsMarker.get().asFile.delete() }
}

val failureTriageMarker = layout.buildDirectory.file("verification/failure-triage.success")
tasks.register<JavaExec>("failureTriageTest") {
    mainClass.set("io.mazewall.build.VerificationActionsTest")
    outputs.file(failureTriageMarker)
    args(failureTriageMarker.get().asFile.absolutePath, "--failure-triage")
    doFirst { failureTriageMarker.get().asFile.delete() }
}

tasks.withType<JavaExec>().matching { it.name in setOf("verificationActionsTest", "failureTriageTest") }.configureEach {
    dependsOn(tasks.named(verification.classesTaskName))
    classpath = verification.runtimeClasspath
    systemProperty("org.gradle.testkit.dir", layout.buildDirectory.dir("test-kit").get().asFile.absolutePath)
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
    inputs.dir(layout.projectDirectory.dir("../profiler/src/main/kotlin/io/mazewall/profiler/triage"))
        .withPropertyName("productionTriageSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.test {
    useJUnitPlatform()
    dependsOn(verificationActionsTest)
    systemProperty("org.gradle.testkit.dir", layout.buildDirectory.dir("test-kit").get().asFile.absolutePath)
}

tasks.named("check") { dependsOn(verificationActionsTest) }
tasks.named("jar") { dependsOn(verificationActionsTest) }
