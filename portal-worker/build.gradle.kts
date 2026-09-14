plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(25)
}

val kotlinExtension = extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>()
val kotlinCompilations = kotlinExtension.target.compilations
val platformJar = project(":platform").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val portalJar = project(":portal").tasks.named<Jar>("jar").flatMap { it.archiveFile }
kotlinCompilations.matching { it.name in setOf("main", "test") }.configureEach {
    compileTaskProvider.configure {
        dependsOn(platformJar, portalJar)
        compilerOptions.freeCompilerArgs.addAll(
            platformJar.zip(portalJar) { platform, portal ->
                listOf(
                    "-Xfriend-paths=${platform.asFile.absolutePath}",
                    "-Xfriend-paths=${portal.asFile.absolutePath}",
                )
            },
        )
    }
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
