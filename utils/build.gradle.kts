plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(22)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    testImplementation(kotlin("test"))
}
