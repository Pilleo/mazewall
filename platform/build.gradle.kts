import java.math.BigDecimal

plugins {
    kotlin("jvm")
    id("info.solidsoft.pitest")
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

val integrationTest = tasks.register<Test>("integrationTest") {
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx256m", "-Xms128m", "-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
    systemProperty("kotest.framework.classpath.scanning.config.disable", "true")
    forkEvery = 1
    testLogging {
        showStandardStreams = true
    }
}

tasks.check {
    dependsOn(integrationTest)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx256m", "-Xms128m", "-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
    systemProperty("kotest.framework.classpath.scanning.config.disable", "true")
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotlinxCoroutines)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.slf4j.nop)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}

pitest {
    junit5PluginVersion.set("1.2.1")
    targetClasses.set(
        setOf(
            "io.mazewall.core.SeccompAction*",
            "io.mazewall.platform.daemon.UnixListenDaemonMachine*",
            "io.mazewall.ffi.networking.SeccompConnectionMachine*",
            "io.mazewall.core.PrctlCommand*",
        ),
    )
    excludedClasses.set(
        setOf(
            "io.mazewall.ffi.nix.*",
            "io.mazewall.ffi.linux.*",
            "io.mazewall.MockNativeEngine*",
        ),
    )
    targetTests.set(
        setOf(
            "io.mazewall.core.SeccompActionTest",
            "io.mazewall.platform.daemon.UnixListenDaemonMachineTest",
            "io.mazewall.ffi.networking.SeccompConnectionMachineTest",
            "io.mazewall.core.PrctlCommandTest",
        ),
    )
    jvmArgs.set(listOf("--enable-native-access=ALL-UNNAMED"))
    timeoutConstInMillis.set(2000)
    timeoutFactor.set(BigDecimal.valueOf(1.25))
    threads.set(System.getProperty("pitest.threads")?.toInt() ?: 4)
}
