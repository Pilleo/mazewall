plugins {
    id("mazewall.test-conventions")
    application
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
    implementation(libs.langchain4j)
    implementation(libs.langchain4j.core)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(kotlin("test"))
}
