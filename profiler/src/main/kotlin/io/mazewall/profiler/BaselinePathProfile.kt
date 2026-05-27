package io.mazewall.profiler

import java.io.File

/**
 * Defines a set of files and directory prefixes that represent expected system or JVM noise
 * which can be explicitly filtered out of a [BillOfBehavior].
 */
data class BaselinePathProfile(
    val exactPaths: Set<String> = emptySet(),
    val pathPrefixes: Set<String> = emptySet(),
) {
    /**
     * Checks if the given path matches this baseline profile.
     */
    fun matches(path: String): Boolean {
        if (exactPaths.contains(path)) return true
        return pathPrefixes.any { prefix -> path.startsWith(prefix) }
    }
}

/**
 * Predefined profiles for filtering common background noise.
 */
object JvmBaselineProfiles {
    /**
     * Creates a profile representing typical JVM bootstrap and system paths.
     * This includes core native system libraries, JVM home, and the active classpath.
     */
    fun jvmBootstrapNoise(): BaselinePathProfile {
        val exactPaths = mutableSetOf(
            "/etc/ld.so.cache",
            "/etc/nsswitch.conf",
        )
        val prefixes = mutableSetOf(
            "/lib",
            "/usr/lib",
            "/lib64",
            "/proc",
            "/sys",
            "/dev",
        )

        val javaHome = System.getProperty("java.home")
        if (javaHome != null) {
            prefixes.add(File(javaHome).absolutePath)
        }

        val classPath = System.getProperty("java.class.path")
        if (classPath != null) {
            classPath.split(File.pathSeparator).forEach { pathEntry ->
                if (pathEntry.isNotEmpty()) {
                    prefixes.add(File(pathEntry).absolutePath)
                }
            }
        }

        return BaselinePathProfile(
            exactPaths = exactPaths,
            pathPrefixes = prefixes,
        )
    }
}
