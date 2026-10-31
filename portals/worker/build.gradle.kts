plugins {
    id("mazewall.quality-conventions")
    `maven-publish`
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") { from(components["java"]) }
    }
}

kotlin {
    jvmToolchain(25)
}

val kotlinExtension = extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>()
val kotlinCompilations = kotlinExtension.target.compilations
val platformJar = project(":platform").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val portalJar = project(":portals:runtime").tasks.named<Jar>("jar").flatMap { it.archiveFile }
kotlinCompilations.matching { it.name in setOf("main", "test") }.configureEach {
    compileTaskProvider.configure {
        dependsOn(platformJar, portalJar)
        (this as org.jetbrains.kotlin.gradle.tasks.BaseKotlinCompile).friendPaths.from(platformJar, portalJar)
    }
}

dependencies {
    api(project(":portals:runtime"))
    implementation(project(":platform"))
    implementation(project(":enforcer"))
    implementation(project(":profiler"))
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform()
}
