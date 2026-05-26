plugins {
    kotlin("jvm")
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "3.4.0"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(22))
    }
}

kotlin {
    jvmToolchain(22)
}






configurations.all {
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    resolutionStrategy {
        force("org.apache.logging.log4j:log4j-api:2.14.1")
        force("org.apache.logging.log4j:log4j-core:2.14.1")
        force("org.apache.logging.log4j:log4j-jul:2.14.1")
        force("org.apache.logging.log4j:log4j-slf4j-impl:2.14.1")
        force("com.thoughtworks.xstream:xstream:1.4.17")
    }
}

dependencies {
    // Import Spring Boot BOM to resolve Starter versions
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.4.0"))

    implementation(project(":enforcer"))

    // Spring Boot Starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    // Database & SQL Injection
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("com.h2database:h2:2.2.224")

    // Log4Shell (vulnerable Log4j 2.14.1)
    implementation("org.apache.logging.log4j:log4j-api:2.14.1")
    implementation("org.apache.logging.log4j:log4j-core:2.14.1")
    implementation("org.apache.logging.log4j:log4j-slf4j-impl:2.14.1")

    // SnakeYAML for CVE-2022-1471 (Explicitly added for YamlImportService)
    implementation("org.yaml:snakeyaml")

    // XStream 1.4.17 for CVE-2021-39144
    implementation("com.thoughtworks.xstream:xstream:1.4.17")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// Disable static analysis for this deliberately vulnerable demo app
tasks.configureEach {
    if (name.contains("detekt", ignoreCase = true) ||
        name.contains("spotbugs", ignoreCase = true) ||
        name.contains("ktlint", ignoreCase = true)
    ) {
        enabled = false
    }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    mainClass.set("demo.vulnapp.VulnAppApplicationKt")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    mainClass.set("demo.vulnapp.VulnAppApplicationKt")
    systemProperty("spring.classformat.ignore", "true")
    systemProperty("org.springframework.boot.logging.LoggingSystem", "none")
}
