plugins {
    kotlin("jvm")
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("io.mazewall.demo.agent.AgentDemoKt")
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "-Djdk.lang.Process.launchMechanism=vfork"
    )
}

dependencies {
    implementation(project(":enforcer"))
    implementation(project(":profiler"))

    // LangChain4j dependencies
    implementation("dev.langchain4j:langchain4j:0.33.0")
    implementation("dev.langchain4j:langchain4j-core:0.33.0")

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(kotlin("test"))
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    testLogging {
        showStandardStreams = true
    }
}

// Disable static analysis for this demo
tasks.configureEach {
    if (name.contains("detekt", ignoreCase = true) ||
        name.contains("spotbugs", ignoreCase = true) ||
        name.contains("ktlint", ignoreCase = true)
    ) {
        enabled = false
    }
}
