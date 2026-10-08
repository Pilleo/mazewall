plugins { `java-library`; `maven-publish` }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(22)) }
publishing {
    publications { create<MavenPublication>("deniedApi") { from(components["java"]); artifactId = "denied-api" } }
}
