package io.mazewall.profiler.hotspot

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
    ): List<String> {
        val lifecycle = requireNotNull(javaClass.getResourceAsStream("/hotspot/production-lifecycle-projection.jsonl")).bufferedReader().use { it.readLines() }
        val payload = requireNotNull(javaClass.getResourceAsStream("/hotspot/measured-event-projection.jsonl")).bufferedReader().use { it.readLines() }

        fun objectValue(text: String) = Json.parseToJsonElement(text) as JsonObject

        fun identify(text: String): JsonObject =
            JsonObject(
            // Synthetic child sessions rebind provider identity along with schema, PID and session.
            objectValue(
                text.replace(
                "e908bb2689936481f8a696dd2347457cc44b6344c1fa78ca65b37772032703eb",
                "b9a9d70db7e37bba07293a635cac94cc540688b85e1f3aa31c2e9ad25cfeecf3",
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
}
