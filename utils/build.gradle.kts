plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Force a fresh JVM for every test to ensure seccomp filters don't contaminate the environment
    forkEvery = 1
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.withType<Test> {
    testLogging {
        showStandardStreams = true
    }
}
