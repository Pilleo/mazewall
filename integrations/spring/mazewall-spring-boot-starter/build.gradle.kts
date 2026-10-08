plugins {
    `java-library`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    api(project(":integrations:spring:mazewall-spring-boot"))
    api("org.springframework.boot:spring-boot-starter")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}
