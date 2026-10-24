package consumer.koogspring

import ai.koog.agents.core.tools.Tool
import ai.koog.serialization.typeToken
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable

@Serializable
data class SpringServiceArguments(val mode: String)

/** Consumer-local synchronous bridge; the existing Spring proxy owns protected dispatch. */
class SpringServiceTool(private val service: JdbcService) : Tool<SpringServiceArguments, String>(
    typeToken<SpringServiceArguments>(), typeToken<String>(), "reviewed_jdbc", "Invoke the fixed approved Spring service",
) {
    override suspend fun execute(args: SpringServiceArguments): String {
        val mode = args.mode
        require(mode.length <= 4096 && mode.isNotBlank() && '\u0000' !in mode && mode.toByteArray(Charsets.UTF_8).size <= 4096) {
            "Mode must be nonblank, NUL-free and at most 4096 UTF-8 bytes"
        }
        // No dispatcher hop: Spring must inspect the original caller; unwrap business interruption afterward.
        val outcome = runInterruptible { runCatching { service.run(mode) } }
        val result = requireNotNull(outcome.getOrThrow()) { "Approved service returned null" }
        require(result.length <= 65536 && result.toByteArray(Charsets.UTF_8).size <= 65536) { "Result exceeds 65536 UTF-8 bytes" }
        return result
    }
}
