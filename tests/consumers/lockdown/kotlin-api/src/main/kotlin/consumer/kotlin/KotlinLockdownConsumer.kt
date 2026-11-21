package consumer.kotlin

import io.mazewall.lockdown.Mazewall
import io.mazewall.lockdown.ProcessPolicy
import io.mazewall.lockdown.RuntimeProfile
import java.io.IOException

fun main() {
    val pre = ProcessBuilder("/bin/echo", "pre-kotlin-ok").start()
    check(pre.waitFor() == 0) { "Pre-install /bin/echo failed" }

    val installed = Mazewall.installOnProcess(ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT))
    check(installed.filterDepth == 1) { "Expected filterDepth == 1, got ${installed.filterDepth}" }
    check(installed.architecture in setOf("amd64", "aarch64")) { "Unexpected arch: ${installed.architecture}" }

    val repeated = Mazewall.installOnProcess(ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT))
    check(repeated.filterDepth == 1) { "Expected idempotent filterDepth == 1" }

    val blocked =
        try {
            ProcessBuilder("/bin/echo", "blocked-kotlin").start()
            error("ProcessBuilder unexpectedly succeeded after lockdown")
        } catch (expected: IOException) {
            expected
        }
    val msg = blocked.message.orEmpty()
    check(msg.contains("Operation not permitted") || msg.contains("error=1") || msg.contains("errno=1")) {
        "Expected EPERM message, got: $msg"
    }
    println("KOTLIN_LOCKDOWN_OK")
}
