plugins {
    kotlin("jvm")
    `java-gradle-plugin`
}

kotlin {
    jvmToolchain(25)
}

val portalCodegenKotlin = extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>()
val portalCodegenPortalJar = project(":portals:runtime").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val portalCodegenPlatformJar = project(":platform").tasks.named<Jar>("jar").flatMap { it.archiveFile }
portalCodegenKotlin.target.compilations.matching { it.name == "test" }.configureEach {
    compileTaskProvider.configure {
        dependsOn(portalCodegenPortalJar, portalCodegenPlatformJar)
        compilerOptions.freeCompilerArgs.addAll(
            portalCodegenPortalJar.zip(portalCodegenPlatformJar) { portal, platform ->
                listOf(
                    "-Xfriend-paths=${portal.asFile.absolutePath}",
                    "-Xfriend-paths=${platform.asFile.absolutePath}",
                )
            },
        )
    }
}

dependencies {
    implementation(libs.kotlinGradlePlugin)
    compileOnly(kotlin("reflect"))
    implementation(libs.kotlinpoet)
    implementation(project(":portals:runtime"))
    implementation(project(":portals:worker"))
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
    testImplementation(kotlin("reflect"))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

gradlePlugin {
    plugins {
        create("portalCodegen") {
            id = "io.mazewall.portal.codegen"
            implementationClass = "io.mazewall.portal.codegen.PortalCodegenPlugin"
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.javaParameters.set(true)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(":portals:runtime:jar", ":platform:jar", ":enforcer:jar")
    systemProperty(
        "portal.codegen.portalJar",
        project(":portals:runtime")
            .tasks
            .named<Jar>("jar")
            .flatMap { it.archiveFile }
            .get()
            .asFile,
    )
    systemProperty(
        "portal.codegen.platformJar",
        project(":platform")
            .tasks
            .named<Jar>("jar")
            .flatMap { it.archiveFile }
            .get()
            .asFile,
    )
    systemProperty(
        "portal.codegen.enforcerJar",
        project(":enforcer")
            .tasks
            .named<Jar>("jar")
            .flatMap { it.archiveFile }
            .get()
            .asFile,
    )
}
