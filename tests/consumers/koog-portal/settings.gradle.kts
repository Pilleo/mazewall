pluginManagement {
    plugins {
        kotlin("jvm") version "2.4.20"
        kotlin("plugin.serialization") version "2.4.20"
    }
    repositories { gradlePluginPortal(); mavenCentral() }
}
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
rootProject.name = "koog-portal-consumer"
