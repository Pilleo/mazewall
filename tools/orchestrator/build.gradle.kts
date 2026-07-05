plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinPluginSerialization)
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlinxSerialization)
    implementation(libs.kotlinxCoroutines)
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("io.mazewall.orchestrator.OrchestratorDaemonKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootProject.projectDir
    // Ensure terminal colors and bells propagate
    environment("TERM", System.getenv("TERM") ?: "xterm")
}
