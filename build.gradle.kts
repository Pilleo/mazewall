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
        systemProperty("io.contained.test", "true")
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
                    "io.contained.profiler.ProfilerDaemon*",
                    "io.contained.seccomp.SeccompEngine*",
                    "io.contained.profiler.IterativeProfiler*",
                    "io.contained.Arch*",
                    "io.contained.Platform*",
                    "io.contained.profiler.Profiler*",
                    "io.contained.landlock.Landlock*",
                    "io.contained.seccomp.PureJavaBpfEngine*"
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
                    "io.contained.landlock.Landlock*",
                    "io.contained.seccomp.PureJavaBpfEngine*",
                    "io.contained.Platform*"
                )
                limit {
                    counter = "INSTRUCTION"
                    value = "COVEREDRATIO"
                    minimum = "0.60".toBigDecimal()
                }
            }
            rule {
                element = "CLASS"
                includes = listOf("io.contained.Arch*")
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
