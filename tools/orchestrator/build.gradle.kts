plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinPluginSerialization)
    application
}

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlinxSerialization)
    implementation(libs.kotlinxCoroutines)
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

application {
    mainClass.set("io.mazewall.orchestrator.OrchestratorDaemonKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootProject.projectDir
    // Ensure terminal colors and bells propagate
    environment("TERM", System.getenv("TERM") ?: "xterm")
    
    // Explicitly forward FORCE_TASK env variable or project property to the application JVM
    val forceTask = System.getenv("FORCE_TASK") ?: System.getProperty("FORCE_TASK")
    if (forceTask != null) {
        environment("FORCE_TASK", forceTask)
    }
    if (project.hasProperty("forceTask")) {
        environment("FORCE_TASK", project.property("forceTask").toString())
    }
}

val checkBacklog by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Validates backlog issue YAML frontmatters, required fields, and dependency references"
    dependsOn(tasks.compileKotlin)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.mazewall.orchestrator.BacklogValidatorKt")
    args = listOf(rootProject.projectDir.absolutePath)
}

tasks.named("check") {
    dependsOn(checkBacklog)
}

tasks.test {
    useJUnitPlatform()
}
