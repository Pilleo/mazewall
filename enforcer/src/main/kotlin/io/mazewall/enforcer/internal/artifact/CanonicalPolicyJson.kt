package io.mazewall.enforcer.internal.artifact

import io.mazewall.enforcer.api.CanonicalPolicyArtifact
import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.enforcer.api.PolicyScope
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.platform.lifecycle.SandboxedPath
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The schema encodes the existing definition; compilation remains the canonical BPF pipeline. */
internal object CanonicalPolicyJson {
    private const val VERSION = 1
    private const val MAX_ACTION_DATA = 65_535

    fun encode(artifact: CanonicalPolicyArtifact): String {
        val policy = artifact.definition
        require(policy.enforceLandlock || (policy.allowedFsReadPaths.isEmpty() && policy.allowedFsWritePaths.isEmpty())) { "Disabled filesystem enforcement cannot carry grants" }
        return buildJsonObject {
            put("formatVersion", VERSION)
            put(
                "scope",
                when (artifact) {
                is CanonicalPolicyArtifact.Worker -> "WORKER"
                is CanonicalPolicyArtifact.Process -> "PROCESS"
            },
            )
            put("runtime", artifact.runtime.name)
            put("defaultAction", encodeAction(policy.defaultAction))
            put(
                "syscalls",
                JsonArray(
                    policy.syscallActions.entries.sortedBy { it.key.name }.map { (syscall, action) ->
                buildJsonObject {
                    put("name", syscall.name)
                    put("action", encodeAction(action))
                }
            },
                ),
            )
            put(
                "arguments",
                buildJsonObject {
                put("executableMappings", policy.allowMmapExec)
                put("nonThreadClone", policy.allowNonThreadClone)
                put("unsafePrctl", policy.allowUnsafePrctl)
                put("cetLock", policy.lockIntelCet)
            },
            )
            put(
                "filesystem",
                buildJsonObject {
                put(
                    "kind",
                    when {
                    !policy.enforceLandlock -> "UNRESTRICTED"
                    policy.allowedFsReadPaths.isEmpty() && policy.allowedFsWritePaths.isEmpty() -> "DENY_ALL"
                    else -> "ALLOW_RESOURCES"
                },
                )
                put("read", strings(policy.allowedFsReadPaths.map { it.value }.sorted()))
                put("write", strings(policy.allowedFsWritePaths.map { it.value }.sorted()))
            },
            )
            put(
                "diagnostics",
                buildJsonObject {
                put("phrases", strings(policy.customViolationPhrases))
                put(
                    "regexes",
                    JsonArray(
                        policy.customViolationRegexes.map { expression ->
                    buildJsonObject {
                    put("pattern", expression.pattern)
                    put("options", strings(expression.options.map { it.name }.sorted()))
                }
                },
                    ),
                )
            },
            )
        }.toString()
    }

    fun decode(document: String): CanonicalPolicyArtifact {
        val root = StrictArtifactJson.parse(document).fields("formatVersion", "scope", "runtime", "defaultAction", "syscalls", "arguments", "filesystem", "diagnostics")
        require(root.getValue("formatVersion").intValue() == VERSION) { "Unsupported policy artifact version" }
        val runtime = RuntimeProfile.valueOf(root.getValue("runtime").stringValue())
        return when (val scope = root.getValue("scope").stringValue()) {
            "WORKER" -> CanonicalPolicyArtifact.Worker(runtime, definition<PolicyScope.ThreadLocalOnly>(root))
            "PROCESS" -> CanonicalPolicyArtifact.Process(runtime, definition<PolicyScope.ProcessWideSafe>(root))
            else -> throw IllegalArgumentException("Unknown artifact scope: $scope")
        }
    }

    private fun <S : PolicyScope> definition(root: JsonObject): PolicyDefinition<S> {
        val args = root.getValue("arguments").objectValue().fields("executableMappings", "nonThreadClone", "unsafePrctl", "cetLock")
        val filesystem = root.getValue("filesystem").objectValue().fields("kind", "read", "write")
        val reads = paths(filesystem, "read")
        val writes = paths(filesystem, "write")
        val enforced = when (filesystem.getValue("kind").stringValue()) {
            "UNRESTRICTED" -> {
                require(reads.isEmpty() && writes.isEmpty()) { "Unrestricted filesystem intent cannot contain grants" }
                false
            }
            "DENY_ALL" -> {
                require(reads.isEmpty() && writes.isEmpty()) { "Deny-all filesystem intent cannot contain grants" }
                true
            }
            "ALLOW_RESOURCES" -> {
                require(reads.isNotEmpty() || writes.isNotEmpty()) { "Empty resource grants must use DENY_ALL" }
                true
            }
            else -> throw IllegalArgumentException("Unknown filesystem intent")
        }
        val syscalls = LinkedHashMap<Syscall, SeccompAction>()
        root.getValue("syscalls").arrayValue().forEach { entry ->
            val rule = entry.objectValue().fields("name", "action")
            val syscall = Syscall.valueOf(rule.getValue("name").stringValue())
            require(syscall !in syscalls) { "Duplicate syscall rule: $syscall" }
            syscalls[syscall] = decodeAction(rule.getValue("action").objectValue())
        }
        val diagnostics = root.getValue("diagnostics").objectValue().fields("phrases", "regexes")
        val expressions = diagnostics.getValue("regexes").arrayValue().map { entry ->
            val rule = entry.objectValue().fields("pattern", "options")
            Regex(
                rule.getValue("pattern").stringValue(),
                rule
                .getValue("options")
                .arrayValue()
                .map { RegexOption.valueOf(it.stringValue()) }
                .toSet(),
            )
        }
        return PolicyDefinition(
            defaultAction = decodeAction(root.getValue("defaultAction").objectValue()),
            syscallActions = syscalls,
            allowMmapExec = args.getValue("executableMappings").booleanValue(),
            allowNonThreadClone = args.getValue("nonThreadClone").booleanValue(),
            allowUnsafePrctl = args.getValue("unsafePrctl").booleanValue(),
            lockIntelCet = args.getValue("cetLock").booleanValue(),
            allowedFsReadPaths = reads,
            allowedFsWritePaths = writes,
            enforceLandlock = enforced,
            customViolationPhrases = diagnostics.getValue("phrases").arrayValue().map { it.stringValue() },
            customViolationRegexes = expressions,
        )
    }

    private fun paths(
        root: JsonObject,
        key: String,
    ): Set<SandboxedPath> {
        val values = root.getValue(key).arrayValue().map { it.stringValue() }
        require(values.distinct().size == values.size) { "Duplicate artifact resource" }
        return values
            .map { path ->
            require(
                java.nio.file.Path
                .of(path)
                .isAbsolute,
            ) { "Artifact resources require absolute paths" }
            SandboxedPath.of(path, allowNonExistent = true)
        }.toSet()
    }

    private fun strings(values: List<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

    private fun encodeAction(action: SeccompAction): JsonObject =
        buildJsonObject {
        val kind = when (action) {
            SeccompAction.ACT_ALLOW -> "ALLOW"
            SeccompAction.ACT_LOG -> "LOG"
            SeccompAction.ACT_NOTIFY -> "NOTIFY"
            SeccompAction.ACT_TRAP -> "TRAP"
            SeccompAction.ACT_KILL_THREAD -> "KILL_THREAD"
            SeccompAction.ACT_KILL_PROCESS -> "KILL_PROCESS"
            is SeccompAction.ACT_ERRNO -> "ERRNO"
            is SeccompAction.ACT_TRACE -> "TRACE"
        }
        put("kind", kind)
        when (action) {
            is SeccompAction.ACT_ERRNO -> {
                require(action.errno in 1..MAX_ACTION_DATA)
                put("data", action.errno)
            }
            is SeccompAction.ACT_TRACE -> {
                require(action.traceId in 0..MAX_ACTION_DATA)
                put("data", action.traceId)
            }
            SeccompAction.ACT_ALLOW, SeccompAction.ACT_LOG, SeccompAction.ACT_NOTIFY, SeccompAction.ACT_TRAP, SeccompAction.ACT_KILL_THREAD, SeccompAction.ACT_KILL_PROCESS -> Unit
        }
    }

    private fun decodeAction(value: JsonObject): SeccompAction {
        val kind = value.getValue("kind").stringValue()
        if (kind == "ERRNO" || kind == "TRACE") {
            value.fields("kind", "data")
            val data = value.getValue("data").intValue()
            require(data in (if (kind == "ERRNO") 1 else 0)..MAX_ACTION_DATA) { "Invalid action data" }
            return if (kind == "ERRNO") SeccompAction.ACT_ERRNO(data) else SeccompAction.ACT_TRACE(data)
        }
        value.fields("kind")
        return when (kind) {
            "ALLOW" -> SeccompAction.ACT_ALLOW
            "LOG" -> SeccompAction.ACT_LOG
            "NOTIFY" -> SeccompAction.ACT_NOTIFY
            "TRAP" -> SeccompAction.ACT_TRAP
            "KILL_THREAD" -> SeccompAction.ACT_KILL_THREAD
            "KILL_PROCESS" -> SeccompAction.ACT_KILL_PROCESS
            else -> throw IllegalArgumentException("Unknown action: $kind")
        }
    }
}
