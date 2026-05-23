plugins {
    kotlin("jvm") version "2.3.21" apply false
    id("jacoco")
}

subprojects {
    apply(plugin = "jacoco")

    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoPluginExtension> {
        toolVersion = "0.8.14"
    }

    tasks.withType<Test>().configureEach {
        systemProperty("io.mazewall.test", "true")
        finalizedBy(tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>())
    }

    tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>().configureEach {
        dependsOn(tasks.withType<Test>())
        reports {
            xml.required.set(true)
            html.required.set(true)
        }

        finalizedBy(tasks.withType<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>())
    }

    tasks.withType<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>().configureEach {
        violationRules {
            // Core classes must meet 80% instruction coverage
            rule {
                element = "CLASS"
                excludes = listOf(
                    "io.mazewall.profiler.ProfilerDaemon*",
                    "io.mazewall.seccomp.SeccompEngine*",
                    "io.mazewall.profiler.IterativeProfiler*",
                    "io.mazewall.Arch*",
                    "io.mazewall.Platform*",
                    "io.mazewall.profiler.Profiler*",
                    "io.mazewall.landlock.Landlock*",
                    "io.mazewall.seccomp.PureJavaBpfEngine*"
                )
                limit {
                    counter = "INSTRUCTION"
                    value = "COVEREDRATIO"
                    minimum = "0.80".toBigDecimal()
                }
            }
            rule {
                element = "CLASS"
                includes = listOf(
                    "io.mazewall.landlock.Landlock*",
                    "io.mazewall.seccomp.PureJavaBpfEngine*",
                    "io.mazewall.Platform*"
                )
                limit {
                    counter = "INSTRUCTION"
                    value = "COVEREDRATIO"
                    minimum = "0.60".toBigDecimal()
                }
            }
            rule {
                element = "CLASS"
                includes = listOf("io.mazewall.Arch*")
                limit {
                    counter = "INSTRUCTION"
                    value = "COVEREDRATIO"
                    minimum = "0.40".toBigDecimal()
                }
            }
        }
    }

    plugins.withId("java") {
        tasks.named("check") {
            dependsOn(tasks.withType<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>())
        }
    }
}
