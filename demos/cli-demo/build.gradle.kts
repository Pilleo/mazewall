plugins {
    id("mazewall.test-conventions")
    application
}
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
kotlin {
    jvmToolchain(25)
}

// These in-repository demonstration tests exercise raw attack primitives.  The
// published CLI application still depends only on the enforcer/profiler facades.
val cliDemoPlatformJar = project(":platform").tasks.named<Jar>("jar").flatMap { it.archiveFile }
extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>()
    .target
    .compilations
    .named("test") {
        compileTaskProvider.configure {
            dependsOn(cliDemoPlatformJar)
            compilerOptions.freeCompilerArgs.addAll(
                cliDemoPlatformJar.map { listOf("-Xfriend-paths=${it.asFile.absolutePath}") },
            )
        }
    }
application {
    mainClass.set("demo.DemoAppKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    testImplementation(project(":platform"))
    implementation(project(":enforcer"))
    implementation(project(":profiler"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(kotlin("test"))
    testImplementation(rootProject.sourceSets["sharedTest"].output)
}
