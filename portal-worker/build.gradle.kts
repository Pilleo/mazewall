plugins {
    id("mazewall.quality-conventions")
    id("mazewall.publishing-conventions")
}

dependencies {
    api(project(":portal"))
    implementation(project(":platform"))
    implementation(project(":enforcer"))
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform()
}
