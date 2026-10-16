package io.mazewall.enforcer.api

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse

class EnforcerApiDumpTest {
    @Test
    fun `public enforcer API does not expose containment implementation packages`() {
        val apiDump = sequenceOf(Path.of("api", "enforcer.api"), Path.of("enforcer", "api", "enforcer.api"))
            .firstOrNull(Files::isRegularFile)
            ?.let(Files::readString)
            ?: error("Cannot locate enforcer.api")

        listOf(
            "io/mazewall/enforcer/supervisor/",
            "io/mazewall/ffi/networking/",
            "io/mazewall/landlock/",
            "io/mazewall/seccomp/",
        ).forEach { forbiddenPackage ->
            assertFalse(apiDump.contains(forbiddenPackage), "enforcer.api exposes $forbiddenPackage")
        }
    }
}
