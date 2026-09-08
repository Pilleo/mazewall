plugins {
    id("mazewall.test-conventions")
    application
}
application {
    mainClass.set("demo.DemoAppKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(project(":enforcer"))
    implementation(project(":profiler"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(kotlin("test"))
    testImplementation(rootProject.sourceSets["sharedTest"].output)
}
