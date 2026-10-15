package io.mazewall.verification

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Uses the fleet's flock protocol; Java record locks do not interoperate with it. */
internal object SharedVerificationLock {
    fun resolve(
        repository: Path,
        override: String? = null,
    ): Path {
        if (override != null) return Path.of(override).also { require(it.isAbsolute) { "verificationLock must be absolute" } }
        val builder = ProcessBuilder("git", "-C", repository.toString(), "rev-parse", "--path-format=absolute", "--git-common-dir")
        val names = ProcessBuilder("git", "rev-parse", "--local-env-vars").start()
        names.inputStream
            .bufferedReader()
            .use { it.readLines() }
            .forEach(builder.environment()::remove)
        check(names.waitFor(30, TimeUnit.SECONDS) && names.exitValue() == 0) { "Cannot identify Git-local environment" }
        val child = builder.redirectErrorStream(true).start()
        val common = child.inputStream
            .bufferedReader()
            .use { it.readText() }
            .trim()
        check(child.waitFor(30, TimeUnit.SECONDS) && child.exitValue() == 0) { "Cannot resolve common repository metadata: $common" }
        return Path.of(common).resolve("mazewall/verification.lock")
    }

    fun acquire(path: Path): AutoCloseable {
        Files.createDirectories(path.toAbsolutePath().parent)
        val owner = ProcessBuilder("flock", "-x", "-w", "900", path.toString(), "sh", "-c", "printf 'locked\\n'; cat >/dev/null").start()
        val ready = CompletableFuture.supplyAsync { owner.inputStream.bufferedReader().readLine() }
        try {
            check(ready.get(905, TimeUnit.SECONDS) == "locked") { "Verification lock acquisition failed: $path" }
        } catch (failure: Throwable) {
            owner.outputStream.close()
            if (owner.isAlive) owner.destroyForcibly()
            owner.waitFor(5, TimeUnit.SECONDS)
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            throw failure
        }
        return AutoCloseable {
            owner.outputStream.close()
            if (!owner.waitFor(5, TimeUnit.SECONDS)) {
                owner.destroyForcibly()
                check(owner.waitFor(5, TimeUnit.SECONDS)) { "Verification lock owner did not retire" }
            }
        }
    }
}
