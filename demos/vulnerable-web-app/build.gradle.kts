plugins {
    id("mazewall.test-conventions")
    alias(libs.plugins.kotlinPluginSpring)
    alias(libs.plugins.springBoot)
    id("jacoco")
}


configurations.all {
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    resolutionStrategy {
        force(libs.vulnerable.log4j.api.get().toString())
        force(libs.vulnerable.log4j.core.get().toString())
        force(libs.vulnerable.log4j.jul.get().toString())
        force(libs.vulnerable.log4j.slf4j.get().toString())
        force(libs.vulnerable.xstream.get().toString())
    }
}

dependencies {
    // Import Spring Boot BOM to resolve Starter versions
    implementation(platform(libs.spring.boot.bom))

    implementation(project(":enforcer"))
    testImplementation(project(":profiler"))

    // Spring Boot Starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    // Database & SQL Injection
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation(libs.h2)

    // Log4Shell (vulnerable Log4j 2.14.1)
    implementation(libs.vulnerable.log4j.api)
    implementation(libs.vulnerable.log4j.core)
    implementation(libs.vulnerable.log4j.slf4j)

    // SnakeYAML for CVE-2022-1471 (Explicitly added for YamlImportService)
    implementation("org.yaml:snakeyaml")

    // XStream 1.4.17 for CVE-2021-39144
    implementation(libs.vulnerable.xstream)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
    dependsOn(tasks.named("bootJar"))
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
    archiveFileName.set("vulnerable-app.jar")
}

val extractJacocoAgent = tasks.register<Copy>("extractJacocoAgent") {
    // org.jacoco.agent is a wrapper jar containing jacocoagent.jar inside.
    // Extract it once so bootRun can reference it as a -javaagent.
    val jacocoAgent = configurations.named("jacocoAgent")
    from(jacocoAgent.map { zipTree(it.singleFile) }) {
        include("jacocoagent.jar")
    }
    into(layout.buildDirectory.dir("jacoco"))
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    mainClass.set("demo.vulnapp.VulnAppApplicationKt")
    systemProperty("spring.classformat.ignore", "true")
    systemProperty("org.springframework.boot.logging.LoggingSystem", "none")
    dependsOn(extractJacocoAgent)
    val jacocoExecFile = layout.buildDirectory.file("jacoco/bootRun.exec")
    val jacocoAgentFile = layout.buildDirectory.file("jacoco/jacocoagent.jar")
    // Attach jacocoagent.jar as a -javaagent so runtime coverage is written to
    // build/jacoco/bootRun.exec. We must use doFirst because jvmArgs() wired
    // to a Provider<List<String>> is not supported by BootRun's JavaForkOptions.
    doFirst("attachJacocoAgent") {
        val execFile = jacocoExecFile.get().asFile
        val agentFile = jacocoAgentFile.get().asFile
        execFile.parentFile.mkdirs()
        jvmArgs(
            "-javaagent:${agentFile.absolutePath}" +
                "=destfile=${execFile.absolutePath}" +
                ",includes=demo.vulnapp.*" +
                ",output=file"
        )
    }
}



tasks.named<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoTestReport") {
    executionData.setFrom(layout.buildDirectory.file("jacoco/bootRun.exec"))
    sourceDirectories.setFrom(files("src/main/kotlin"))
    classDirectories.setFrom(files(layout.buildDirectory.dir("classes/kotlin/main")))
    reports {
        html.required.set(true)
        xml.required.set(false)
        csv.required.set(false)
    }
}
