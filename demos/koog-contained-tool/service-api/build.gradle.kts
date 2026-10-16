plugins { `java-library`; `maven-publish` }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(22)); withSourcesJar() }
publishing {
    publications { create<MavenPublication>("serviceApi") { from(components["java"]) } }
    repositories { maven { name = "consumerStage"; url = uri(layout.buildDirectory.dir("consumer-stage")) } }
}
