plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    `java-library`
    `maven-publish`
}

kotlin { jvmToolchain(25) }
java { withSourcesJar() }

dependencyLocking {
    lockAllConfigurations()
    lockMode.set(LockMode.STRICT)
}

dependencies {
    api(project(":portals:runtime"))
    api("ai.koog:agents-tools:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }
publishing {
    publications { create<MavenPublication>("koog") { from(components["java"]) } }
    repositories {
        maven {
            name = "consumerStage"
            url = uri(layout.buildDirectory.dir("consumer-stage"))
        }
    }
}
