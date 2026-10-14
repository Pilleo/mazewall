import org.gradle.api.publish.PublishingExtension

plugins {
    id("mazewall.base-conventions")
    `maven-publish`
}

extensions.configure<PublishingExtension> {
    repositories {
        providers.environmentVariable("GITHUB_ACTOR").orNull?.let { actor ->
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/Pilleo/mazewall")
                credentials {
                    username = actor
                    password = providers.environmentVariable("GITHUB_TOKEN").orNull
                }
            }
        }
    }
}
