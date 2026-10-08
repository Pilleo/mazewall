plugins {
    `java-library`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

dependencies {
    api(project(":enforcer"))
    api(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    api("org.springframework:spring-aop")
    api("org.springframework:spring-tx")
    api("org.springframework:spring-context")
    api("org.springframework:spring-jdbc")
    compileOnly("org.springframework.security:spring-security-core")
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.springframework.boot:spring-boot-autoconfigure")
    testImplementation("org.assertj:assertj-core")
    testImplementation("com.h2database:h2")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}
