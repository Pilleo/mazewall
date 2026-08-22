plugins {
    kotlin("jvm")
    `java-gradle-plugin`
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    compileOnly(libs.kotlinGradlePlugin)
    implementation(libs.kotlinpoet)
    testImplementation(gradleTestKit())
    testImplementation(project(":portal"))
    testImplementation(kotlin("test"))
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

tasks.test {
    useJUnitPlatform()
}
