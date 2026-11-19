package io.mazewall.profiler.hotspot

import io.mazewall.profiler.api.HotSpotObservationTarget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider

internal object HotSpotChildFixture {
    private val classes = Files.createTempDirectory("hotspot-child-test-").also { directory ->
        val source = directory.resolve("HotSpotTestCollector.java")
        requireNotNull(javaClass.getResourceAsStream("/hotspot/HotSpotTestCollector.java")).use { Files.copy(it, source) }
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", directory.toString(), source.toString()) == 0)
    }

    fun argv(
        mode: String,
        directory: Path,
        wrongGeneration: Boolean = false,
        observedTarget: HotSpotObservationTarget? = null,
    ): List<String> {
        if (observedTarget != null) return observedArgv(mode, directory, observedTarget)
        val lifecycle = requireNotNull(javaClass.getResourceAsStream("/hotspot/production-lifecycle-projection.jsonl")).bufferedReader().use { it.readLines() }
        val payload = requireNotNull(javaClass.getResourceAsStream("/hotspot/measured-event-projection.jsonl")).bufferedReader().use { it.readLines() }

        fun objectValue(text: String) = Json.parseToJsonElement(text) as JsonObject

        fun identify(text: String): JsonObject =
            JsonObject(
            // Synthetic child sessions rebind provider identity along with schema, PID and session.
            objectValue(
                text.replace(
                "e908bb2689936481f8a696dd2347457cc44b6344c1fa78ca65b37772032703eb",
                "22f66bf56bb3b9a00c47186a53bff07ab932ca48dff8b3900666348685ff5035",
            ),
            ).toMutableMap().apply {
            put("schema_version", JsonPrimitive(2))
            put("session_id", JsonPrimitive("test-session"))
            if (containsKey("event_id")) put("event_id", JsonPrimitive("test-session:8:61"))
        },
        )
        val headers = lifecycle.map { text ->
            JsonObject(
                identify(text).toMutableMap().apply {
                put(
                    "lifecycle",
                    JsonObject(
                        (getValue("lifecycle") as JsonObject).toMutableMap().apply {
                    put("pid", JsonPrimitive(123))
                    if (containsKey("process_start_ticks")) put("process_start_ticks", JsonPrimitive(if (wrongGeneration) 457 else 456))
                },
                    ),
                )
            },
            )
        }
        val event = JsonObject(
            identify(payload[0]).toMutableMap().apply {
            put(
                "event",
                JsonObject(
                    (getValue("event") as JsonObject).toMutableMap().apply {
                put("host_pid", JsonPrimitive(123))
                put("process_start_ticks", JsonPrimitive(456))
                put("session_id", JsonPrimitive("test-session"))
                put("event_id", JsonPrimitive("test-session:8:61"))
            },
                ),
            )
        },
        )
        val summary = JsonObject(
            identify(payload[2]).toMutableMap().apply {
            put(
                "summary",
                JsonObject(
                    (getValue("summary") as JsonObject).toMutableMap().apply {
                put("event_finalization_observation", objectValue("""{"producers_quiesced":true,"buffer_drained":true,"receiver_joined":true,"final_counter_read":true,"resources_retired":true}"""))
                put("definition_stop_observation", objectValue("""{"reader_stopped":true,"consumer_stopped":true,"buffers_drained":true,"auxiliary_stopped":true}"""))
            },
                ),
            )
        },
        )
        val error = JsonObject(
            headers[0].toMutableMap().apply {
            put("kind", JsonPrimitive("collector_error"))
            put(
                "lifecycle",
                JsonObject(
                    (getValue("lifecycle") as JsonObject).toMutableMap().apply {
                put("message", JsonPrimitive("native permission failure"))
                put("errno", JsonPrimitive(13))
            },
                ),
            )
        },
        )
        val file = directory.resolve("$mode.jsonl")
        Files.writeString(file, listOf(headers[0], headers[1], event, identify(payload[1]), summary, error).joinToString("\n") + "\n")
        return listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classes.toString(), "HotSpotTestCollector", mode, file.toString())
    }

    private fun observedArgv(
        mode: String,
        directory: Path,
        target: HotSpotObservationTarget,
    ): List<String> {
        val text = requireNotNull(javaClass.getResourceAsStream("/hotspot/observed-consumer.jsonl"))
            .bufferedReader()
            .use { it.readText() }
            .replace("\"pid\":123", "\"pid\":${target.pid.value}")
            .replace("\"host_pid\":123", "\"host_pid\":${target.pid.value}")
            .replace("\"process_start_ticks\":456", "\"process_start_ticks\":${target.processStartTicks}")
            .replace("\"cgroup_id\":789", "\"cgroup_id\":${target.cgroupId}")
            .replace("\"mount_namespace\":42", "\"mount_namespace\":${target.mountNamespace}")
        val rows = text.lines().filter { it.isNotEmpty() }
        val failure = rows
            .first()
            .replace("\"kind\":\"session_started\"", "\"kind\":\"collector_error\"")
            .replace("\"lifecycle\":{", "\"lifecycle\":{\"errno\":5,\"errno_causes\":[5,13],\"message\":\"synthetic original native failure\",")
        val file = directory.resolve("$mode-observed.jsonl")
        Files.writeString(file, (rows + failure).joinToString("\n", postfix = "\n"))
        return listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classes.toString(), "HotSpotTestCollector", mode, file.toString())
    }
}
