import io.mazewall.build.BuildPolicy

plugins {
    id("mazewall.base-conventions")
    `java-library`
    kotlin("jvm")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(BuildPolicy.TOOLCHAIN_VERSION))
}

kotlin {
    jvmToolchain(BuildPolicy.TOOLCHAIN_VERSION)
}

// Internal cross-module friend grants name the packaged JAR, not class-directory variants.
configurations.configureEach {
    if (isCanBeResolved && name.endsWith("CompileClasspath", ignoreCase = true)) {
        attributes.attribute(
            org.gradle.api.attributes.LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
            objects.named(org.gradle.api.attributes.LibraryElements.JAR),
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(BuildPolicy.RELEASE_VERSION)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_22)
        freeCompilerArgs.add("-Xcontext-parameters")
        freeCompilerArgs.add("-opt-in=io.mazewall.platform.lifecycle.MazewallInternal")
    }
}
