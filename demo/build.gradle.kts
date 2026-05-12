plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("demo.DemoAppKt")
}

kotlin {
    jvmToolchain(22)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.run {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(project(":utils"))
    testImplementation(kotlin("test"))
}
