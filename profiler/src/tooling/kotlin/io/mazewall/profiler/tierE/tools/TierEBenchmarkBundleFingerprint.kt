package io.mazewall.profiler.tierE.tools

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Input identity compatible with the saved v2 bundle fingerprint contract. */
public object TierEBenchmarkBundleFingerprint {
    public fun read(
        repository: Path,
        usdt: Boolean,
        nativeBuildType: String,
    ): String {
        require(nativeBuildType.matches(Regex("[A-Za-z0-9_]+"))) { "Invalid native build type" }

        fun command(vararg args: String): String {
            val process = ProcessBuilder(*args)
                .directory(repository.toFile())
                .redirectErrorStream(true)
                .apply {
                environment()["GIT_MASTER"] = "1"
            }.start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) {
                "Bundle fingerprint command failed: ${args.first()}"
            }
            return text
        }

        require(command("git", "rev-parse", "--is-inside-work-tree").trim() == "true") { "Bundle source is not a Git checkout" }
        val scope = arrayOf(
            "gradle",
            "buildSrc",
            "enforcer",
            "platform",
            "profiler",
            "profiler-native",
            "gradlew",
            "gradlew.bat",
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
        )
        val tracked = command("git", "ls-files", "--cached", "--", *scope).lineSequence().filter(String::isNotBlank)
        val others = command("git", "ls-files", "--others", "--exclude-standard", "--", *scope.take(6).toTypedArray())
            .lineSequence()
            .filter(String::isNotBlank)
        val paths = (tracked + others).distinct().sorted().toList()
        val input = ByteArrayOutputStream()

        fun write(text: String) = input.write(text.toByteArray(Charsets.UTF_8))
        write("tier-e-benchmark-bundle-v2\u0000usdt=$usdt\u0000native_build_type=$nativeBuildType\u0000")
        write("cmake=${command("cmake", "--version").lineSequence().first()}\u0000")
        write("compiler=${command("c++", "--version").lineSequence().first()}\u0000")
        write("java=${command("java", "-version").lineSequence().first()}\u0000")
        for (path in paths) {
            write("path=$path\u0000")
            write(command("sha256sum", path))
        }
        return MessageDigest
            .getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
