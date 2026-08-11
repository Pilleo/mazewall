package io.mazewall.enforcer.supervisor

import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.NoSuchFileException
import java.io.FileNotFoundException
import java.util.jar.JarFile
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.InvalidPathException
import java.lang.management.ManagementFactory
import java.util.logging.Logger

/**
 * Shared utility for resolving and caching safe bypass paths for Seccomp daemons.
 * This ensures both the Supervisor and Profiler use the same robust logic to avoid
 * circular JVM safepoint/ClassLoader deadlocks during tracee classloading or JIT execution.
 */
public object BypassPaths {
    private val logger = Logger.getLogger(BypassPaths::class.java.name)

    public fun toRealPathWithFallback(path: Path): Path {
        val abs = path.toAbsolutePath().normalize()
        var current = abs
        val nonExistentParts = mutableListOf<String>()
        while (current.parent != null) {
            try {
                val real = current.toRealPath()
                var resolved = real
                for (i in nonExistentParts.indices.reversed()) {
                    resolved = resolved.resolve(nonExistentParts[i])
                }
                return resolved.normalize()
            } catch (e: NoSuchFileException) {
                nonExistentParts.add(current.fileName.toString())
                current = current.parent ?: break
            } catch (e: FileNotFoundException) {
                nonExistentParts.add(current.fileName.toString())
                current = current.parent ?: break
            }
        }
        var resolved = abs.root ?: abs
        for (i in nonExistentParts.indices.reversed()) {
            resolved = resolved.resolve(nonExistentParts[i])
        }
        return resolved.normalize()
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    public val safeBypassPaths: List<Path> = mutableListOf<Path>().apply {
        fun addPathAndReal(path: Path) {
            val abs = path.toAbsolutePath().normalize()
            add(abs)
            try {
                val real = toRealPathWithFallback(abs)
                add(real)
            } catch (e: NoSuchFileException) {
                // Normal, path might not exist
            } catch (e: FileNotFoundException) {
                // Normal, path might not exist
            } catch (e: Exception) {
                logger.warning { "Failed to resolve real path for $abs: ${e.message}" }
            }
        }

        fun parseManifestClassPath(jarPath: Path) {
            try {
                JarFile(jarPath.toFile()).use { jar ->
                    val manifest = jar.manifest ?: return
                    val classPathAttr = manifest.mainAttributes.getValue("Class-Path") ?: return
                    val parentDir = jarPath.parent ?: return
                    for (entry in classPathAttr.split(" ")) {
                        if (entry.isNotEmpty()) {
                            try {
                                val uri = URI(entry)
                                val resolvedPath = if (uri.isAbsolute) {
                                    Paths.get(uri)
                                } else {
                                    parentDir.resolve(uri.path).normalize()
                                }
                                addPathAndReal(resolvedPath)
                            } catch (e: URISyntaxException) {
                                // Syntax error in manifest CP, skip
                            } catch (e: Exception) {
                                logger.warning { "Failed to parse manifest entry $entry in $jarPath: ${e.message}" }
                            }
                        }
                    }
                }
            } catch (e: FileNotFoundException) {
                // Normal if Jar file doesn't exist
            } catch (e: Exception) {
                logger.warning { "Failed to process manifest Class-Path for $jarPath: ${e.message}" }
            }
        }

        try {
            val javaHomeStr = System.getProperty("java.home")
            if (!javaHomeStr.isNullOrEmpty()) {
                addPathAndReal(Paths.get(javaHomeStr))
            }

            try {
                val userHome = System.getProperty("user.home")
                if (!userHome.isNullOrEmpty()) {
                    addPathAndReal(Paths.get(userHome).resolve(".sdkman"))
                }
            } catch (e: Exception) {
                logger.warning { "Failed to add user.home paths: ${e.message}" }
            }

            val cp = System.getProperty("java.class.path")
            if (cp != null) {
                val cpEntries = cp.split(java.io.File.pathSeparator)
                for (entry in cpEntries) {
                    if (entry.isNotEmpty()) {
                        try {
                            val path = Paths.get(entry)
                            addPathAndReal(path)
                            if (entry.endsWith(".jar")) {
                                parseManifestClassPath(path)
                            }
                        } catch (e: InvalidPathException) {
                            // Normal
                        } catch (e: Exception) {
                            logger.warning { "Failed to process classpath entry $entry: ${e.message}" }
                        }
                    }
                }
            }

            // Add javaagent jars to prevent deadlocks during agent instrumentation
            val jvmArgs = ManagementFactory.getRuntimeMXBean().inputArguments
            for (arg in jvmArgs) {
                if (arg.startsWith("-javaagent:")) {
                    val agentPath = arg.substringAfter("-javaagent:").substringBefore("=")
                    if (agentPath.isNotEmpty()) {
                        try {
                            addPathAndReal(Paths.get(agentPath))
                        } catch (e: InvalidPathException) {
                            // Normal
                        } catch (e: Exception) {
                            logger.warning { "Failed to process javaagent argument $arg: ${e.message}" }
                        }
                    }
                }
            }

            // Add CI-specific build directories and test-framework caches to prevent deadlock
            try {
                addPathAndReal(Paths.get("build"))
                addPathAndReal(Paths.get(".gradle"))
            } catch (e: InvalidPathException) {
                // Normal
            } catch (e: Exception) {
                logger.warning { "Failed to add build/.gradle paths: ${e.message}" }
            }

            // Add GRADLE_USER_HOME if set to support container/CI cache directories
            try {
                val gradleUserHome = System.getenv("GRADLE_USER_HOME")
                if (!gradleUserHome.isNullOrEmpty()) {
                    addPathAndReal(Paths.get(gradleUserHome))
                }
            } catch (e: InvalidPathException) {
                // Normal
            } catch (e: Exception) {
                logger.warning { "Failed to add GRADLE_USER_HOME: ${e.message}" }
            }

            // Add /proc and /sys virtual filesystems to prevent GC/JIT thread deadlocks
            try {
                addPathAndReal(Paths.get("/proc"))
                addPathAndReal(Paths.get("/sys"))
            } catch (e: InvalidPathException) {
                // Normal
            } catch (e: Exception) {
                logger.warning { "Failed to add /proc or /sys: ${e.message}" }
            }

            // (Removed user.dir bypass because it breaks profiler tests by bypassing all project files)
        } catch (e: Exception) {
            logger.severe { "Fatal exception during safeBypassPaths initialization: ${e.message}" }
        }
    }

    public fun isBypassPath(path: Path): Boolean {
        val realPath = try { toRealPathWithFallback(path) } catch (_: Exception) { path }
        return safeBypassPaths.any { bypassPath ->
            path.startsWith(bypassPath) || path == bypassPath || realPath.startsWith(bypassPath) || realPath == bypassPath
        }
    }
}
