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
    }

tasks.check {
    dependsOn(integrationTest)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx256m")
    systemProperty("kotest.framework.classpath.scanning.config.disable", "true")
}

dependencies {
    api(project(":platform"))
    implementation(project(":enforcer"))
    // Portal-worker is not on the main compile classpath (broker isolation)
    // but is included at runtime so spawned worker JVMs can load it from the same classpath
    runtimeOnly(project(":portal-worker"))
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
