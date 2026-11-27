package io.mazewall.build

/** Restricts the shared filter to reviewed code/build inputs; documentation stays outside it. */
internal fun ciCodeFilterValid(filter: String): Boolean {
    val expected = setOf(
        "**/*.kt", "**/*.java", "**/*.kts", "**/*.gradle", "gradle/libs.versions.toml",
        "**/gradle.lockfile", "**/settings-gradle.lockfile", "**/verification-metadata.xml",
        "config/spotbugs/**", "**/gradle.properties", "**/Containerfile", "scripts/**",
        ".github/workflows/ci.yml", ".github/workflows/security-data.yml", ".github/ci-code-paths.yml",
        "**/gradlew", "**/gradlew.bat", "**/gradle/wrapper/gradle-wrapper.jar",
        "**/gradle/wrapper/gradle-wrapper.properties",
    )
    val lines = filter.lines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
    val rule = Regex("    - '([^']+)'")
    val paths = lines.drop(1).map { rule.matchEntire(it)?.groupValues?.get(1) ?: return false }
    return lines.firstOrNull() == "code:" && paths.size == expected.size && paths.toSet() == expected
}
