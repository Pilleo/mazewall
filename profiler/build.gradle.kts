plugins {
    kotlin("jvm")
    id("info.solidsoft.pitest")
    alias(libs.plugins.plantuml)
    alias(libs.plugins.kotlinPluginSerialization)
}

kotlin {
    jvmToolchain(25)
}

sourceSets {
    test {
        java.srcDir(rootProject.file("src/sharedTest/kotlin"))
    }
    create("integrationTest") {
        java.srcDir(rootProject.file("src/sharedTest/kotlin"))
        compileClasspath += main.get().output + test.get().output
        runtimeClasspath += main.get().output + test.get().output
    }
}

// Associate integration tests with main and test to allow accessing internal members and test utilities
val kotlinExtension = extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>()
val kotlinCompilations = kotlinExtension.target.compilations
kotlinCompilations.named("integrationTest") {
    associateWith(kotlinCompilations.getByName("main"))
    associateWith(kotlinCompilations.getByName("test"))
}

val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}

val integrationTestRuntimeOnly by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}

val integrationTest =
    tasks.register<Test>("integrationTest") {
        group = "verification"
        testClassesDirs = sourceSets["integrationTest"].output.classesDirs
        classpath = sourceSets["integrationTest"].runtimeClasspath
        useJUnitPlatform()
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx256m", "-Xms128m", "-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
        systemProperty("kotest.framework.classpath.scanning.config.disable", "true")
        forkEvery = 1
    }

tasks.check {
    dependsOn(integrationTest)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx256m", "-Xms128m", "-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
    systemProperty("kotest.framework.classpath.scanning.config.disable", "true")
}

val plantumlConfig by configurations.creating

dependencies {
    plantumlConfig(libs.plantuml.core)
    implementation(project(":enforcer"))
    implementation(libs.kotlinxSerialization)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.runner)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.slf4j.nop)
}


publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}

pitest {
    junit5PluginVersion.set("1.2.1")
    targetClasses.set(setOf("io.mazewall.profiler.*"))

    excludedClasses.set(
        setOf(
            "io.mazewall.profiler.internal.ProfilerDaemon*",
            "io.mazewall.profiler.engine.ProfilerTransport*",
        ),
    )

    jvmArgs.set(listOf("--enable-native-access=ALL-UNNAMED"))

    threads.set(System.getProperty("pitest.threads")?.toInt() ?: 4)
}

classDiagrams {
    plantumlServer = null
    renderClasspath(plantumlConfig)
    defaults {
        style {
            hidePackages()
            theme("spacelab")
            hide("empty members")
        }
        exclude(methods().withNameLike("component*") as io.gitlab.plunts.gradle.plantuml.plugin.matcher.MethodMatcher)
        exclude(methods().withName("copy") as io.gitlab.plunts.gradle.plantuml.plugin.matcher.MethodMatcher)
        exclude(methods().withName("getEntries") as io.gitlab.plunts.gradle.plantuml.plugin.matcher.MethodMatcher)
        exclude(methods().withName("profile") as io.gitlab.plunts.gradle.plantuml.plugin.matcher.MethodMatcher)
        exclude(methods().withName("wrap") as io.gitlab.plunts.gradle.plantuml.plugin.matcher.MethodMatcher)
        exclude(methods().withName("toPolicy") as io.gitlab.plunts.gradle.plantuml.plugin.matcher.MethodMatcher)
        exclude(methods().withName("toDsl") as io.gitlab.plunts.gradle.plantuml.plugin.matcher.MethodMatcher)
        exclude(classes().withNameLike("*\\$*"))
        // Exclude constant/data-heavy/mapper noise
        exclude(classes().withName("io.mazewall.profiler.engine.ProfilerConstantsKt"))
    }

    diagram {
        name("Profiler Class Diagram")
        include(packages().withName("io.mazewall.profiler"))
        writeTo(file("$rootDir/docs/diagrams/profiler_class_diagram.puml"))
        renderTo(file("$rootDir/docs/diagrams/profiler_class_diagram.svg"))
    }
}

tasks.named("generateClassDiagrams") {
    val pumlFile = file("$rootDir/docs/diagrams/profiler_class_diagram.puml")
    val svgFile = file("$rootDir/docs/diagrams/profiler_class_diagram.svg")

    doLast {
        fun cleanup(file: File) {
            if (file.exists()) {
                var content = file.readText()
                // Strip Kotlin value-class mangling hash suffix (e.g. -r9EpL9Y, -LsA-840, etc.)
                content = content.replace(Regex("([a-zA-Z0-9_]+)-[a-zA-Z0-9_-]{7,15}(?=\\b|\\(|$)"), "$1")
                // Strip any leftover internal module access flags (e.g. $io_mazewall_enforcer)
                content = content.replace(Regex("\\\$[a-zA-Z0-9_]+"), "")
                // Fix PlantUML SVG XML parsing error (duplicate data attribute in <g class="entity">)
                content = content.replace(Regex(" data=\"([^\"]*)\" id=\"([^\"]*)\" data=\"([^\"]*)\""), " data-name=\"$1\" id=\"$2\" data-line=\"$3\"")
                file.writeText(content)
            }
        }

        cleanup(pumlFile)
        cleanup(svgFile)
    }
}

tasks.named("build") {
    if (System.getenv("CI") != "true" && System.getenv("MAZEWALL_IN_CONTAINER") != "true") {
        dependsOn("generateClassDiagrams")
    }
}

