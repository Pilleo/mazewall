import org.gradle.api.credentials.PasswordCredentials

pluginManagement {
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "mazewall"
                    url = uri(providers.gradleProperty("consumerRepository").get())
                    if (url.scheme == "https" && url.host == "maven.pkg.github.com") credentials(PasswordCredentials::class)
                }
            }
            filter { includeGroupByRegex("io\\.mazewall(\\..*)?") }
        }
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("jvm") version "2.4.20"
        kotlin("plugin.serialization") version "2.4.20"
        id("io.mazewall.portal.codegen") version providers.gradleProperty("consumerVersion").get()
    }
}
rootProject.name = "koog-document-agent"
include("boundary-fixture")
