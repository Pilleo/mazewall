package io.mazewall.build

/** JVM bridge for the mandatory pre-JAR regression harness. */
object GradleSourceGuardTest {
    @JvmStatic
    fun run() {
        val cases = listOf(
            "doFirst { println(configurations.runtimeClasspath.asPath) }" to emptyList(),
            "doLast { println(\"}\"); println(configurations.runtimeClasspath.singleFile) }" to emptyList(),
            "providers.provider { configurations.runtimeClasspath.singleFile }" to emptyList(),
            "inputs.files(configurations[\"runtimeClasspath\"])" to emptyList(),
            "val eager = configurations.runtimeClasspath.asPath" to listOf(1),
            "doFirst { println(\"ok\") }\nval eager = configurations.runtimeClasspath.singleFile" to listOf(2),
            "doFirst(configurations.runtimeClasspath.asPath) { println(\"later\") }" to listOf(1),
            "providers.provider { configurations.runtimeClasspath.asPath }.get()" to listOf(1),
            "((providers.provider {\n configurations.runtimeClasspath.asPath\n})).get()" to listOf(2),
            "(providers.provider({ configurations.runtimeClasspath.asPath })).get()" to listOf(1),
            "doFirst { ((providers.provider { configurations.runtimeClasspath.asPath })).get() }" to emptyList(),
            "doFirst({ configurations.runtimeClasspath.asPath }())" to listOf(1),
            "val cp = configurations.getByName(\"runtimeClasspath\")\nval eager = cp.files" to listOf(2),
            "configurations[\"runtimeClasspath\"].files" to listOf(1),
            "configurations[\n \"runtimeClasspath\"\n].getFiles()" to listOf(3),
            "@TaskAction fun run() { println(configurations.runtimeClasspath.asPath) }" to emptyList(),
            "@TaskAction fun run(path: String = configurations.runtimeClasspath.asPath) {}" to listOf(1),
            "override fun asArguments(): Iterable<String> =\n listOf(configurations.runtimeClasspath.asPath)" to emptyList(),
            "override fun asArguments(): Iterable<String> = listOf(\"ok\")\nval eager = configurations.runtimeClasspath.asPath" to listOf(2),
            "val note = \"doFirst { .asPath }\"\nval eager = configurations.runtimeClasspath.asPath" to listOf(2),
            "val eager = \"cp=\${configurations.runtimeClasspath.asPath}\"" to listOf(1),
            "doLast { println(\"cp=\${configurations.runtimeClasspath.asPath}\") }" to emptyList(),
            "/* outer /* .asPath */ } */ doFirst { println(configurations.runtimeClasspath.asPath) }" to emptyList(),
            "listOf(providers.provider { configurations.runtimeClasspath.asPath }).get(0)" to emptyList(),
        )
        for ((source, expected) in cases) {
            check(GradleSourceGuard.findings(source, false) == expected) { "Wrong Kotlin decision: $source" }
        }
        check(GradleSourceGuard.findings("def pattern = /literal } .asPath/", true).isEmpty())
        check(GradleSourceGuard.findings("def eager = \"\$configurations.runtimeClasspath.asPath\"", true) == listOf(1))
        check(GradleSourceGuard.findings("@TaskAction void run() { println(configurations.runtimeClasspath.asPath) }", true).isEmpty())
        for (source in listOf("doFirst { println(configurations.runtimeClasspath.asPath)", "val text = \"unterminated", "/* unfinished")) {
            check(runCatching { GradleSourceGuard.findings(source, false) }.exceptionOrNull() is IllegalArgumentException)
        }
        val resources = GradleSourceGuardTest::class.java.getResourceAsStream("/gradle-source-guard.jsonl") ?: error("Missing legacy decision fixtures")
        var total = 0
        resources.bufferedReader().useLines { rows ->
            rows.forEach { row ->
                val fixture = groovy.json.JsonSlurper().parseText(row) as Map<*, *>
                val shared = fixture["shared"] == true
                for (groovy in if (shared) listOf(false, true) else listOf(fixture["groovy"] == true)) {
                    var source = fixture["source"] as String
                    if (groovy) source = source.replace(Regex("\\bval\\b"), "def").replace("listOf(1).forEach", "[1].each")
                    val result = runCatching { GradleSourceGuard.findings(source, groovy) }
                    if (fixture["parseFailure"] == true) check(result.exceptionOrNull() is IllegalArgumentException) { fixture["name"].toString() }
                    else {
                        val found = result.getOrThrow()
                        val expected = (fixture["lines"] as List<*>).map { (it as Number).toInt() }
                        check(if (expected.isNotEmpty()) found == expected else found.isNotEmpty() == ((fixture["status"] as Number).toInt() == 1)) { "Legacy fixture ${fixture["name"]}: $found != $expected" }
                    }
                    total++
                }
            }
        }
        val repository = java.nio.file.Path.of("").toAbsolutePath().parent
        check(GradleSourceGuard.findings(java.nio.file.Files.readString(repository.resolve("integrations/spring/mazewall-spring-boot/build.gradle.kts")), false).isEmpty())
        check(GradleSourceGuard.findings(java.nio.file.Files.readString(repository.resolve("tests/consumers/java-api/build.gradle")), true).isEmpty())
        println("Kotlin source guard: $total retained language fixtures and two actual source scripts passed.")
    }
}
