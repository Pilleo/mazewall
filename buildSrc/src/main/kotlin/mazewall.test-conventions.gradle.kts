import io.mazewall.build.BuildPolicy

plugins {
    id("mazewall.jvm-library-conventions")
}

val testHeap = providers.gradleProperty("mazewall.test.heap").getOrElse(BuildPolicy.DEFAULT_TEST_HEAP)
val testMinHeap = providers.gradleProperty("mazewall.test.minHeap").getOrElse(BuildPolicy.DEFAULT_TEST_MIN_HEAP)
val maxTestForks = providers.gradleProperty("mazewall.test.maxParallelForks").getOrElse(BuildPolicy.DEFAULT_TEST_FORKS).toInt()

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxParallelForks = maxTestForks
    systemProperty("junit.jupiter.execution.timeout.default", "2 m")
    systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD")
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "-Xmx$testHeap",
        "-Xms$testMinHeap",
        "-Dfile.encoding=UTF-8",
        "-Dsun.jnu.encoding=UTF-8",
    )
}
