package io.mazewall.portal.worker

import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.portal.PortalMethod

/**
 * Compatibility registry for callers using the original global startup API.
 *
 * New worker startup builds a local [PortalDispatcherTable]. Legacy registrations are
 * collected only until this registry publishes its immutable compatibility table.
 * Set `-Dio.mazewall.portal.worker.dispatchers` to a comma-separated
 * list of `fqcn.Service=fqcn.ServiceImpl[;fqcn.DispatcherObject]` entries. The
 * dispatcher object defaults to `<simple-name>PortalDispatcher` in the service's
 * package and must expose `METHOD_IDS: IntArray` plus
 * `handle(impl, method, payload, granted): ByteArray`.
 */
public object PortalDispatcherRegistry {
    public typealias Handler = PortalDispatcherHandler

    private var startupBuilder: PortalDispatcherTable.Builder? = PortalDispatcherTable.Builder()

    @Volatile private var publishedTable: PortalDispatcherTable? = null

    @Synchronized
    public fun register(
        ids: IntArray,
        handler: Handler,
    ) {
        startupBuilder().register(ids, handler)
    }

    /** Prevents runtime mutation after startup wiring has completed. */
    @Synchronized
    public fun freeze() {
        publish()
    }

    /** Publishes on first dispatch for compatibility, then routes only through the immutable table. */
    public fun dispatchOrNull(
        method: PortalMethod.Generated,
        payload: ByteArray,
        granted: List<FileDescriptor<FileDescriptorRole.Granted, FdState.Open, FdOwnership>>,
    ): ByteArray? {
        val table = publishedTable ?: synchronized(this) { publishedTable ?: publish() }
        return table.dispatchOrNull(method, payload, granted)
    }

    /**
     * Reflectively wires `Interface=Impl(;Dispatcher)?` pairs. Compatibility bootstrap
     * remains tolerant before publication; callers that require sealed startup call [freeze].
     */
    @Synchronized
    public fun bootstrapFromProperty(raw: String?): Int {
        return startupBuilder().bootstrapFromProperty(raw)
    }

    /** Startup-only bootstrap: malformed or duplicate handlers abort before dispatch is enabled. */
    public fun bootstrapStrict(raw: String?): Int {
        return publishStrict(raw).registeredEntries
    }

    @Synchronized
    internal fun publishStrict(raw: String?): PortalDispatcherPublication {
        val count = startupBuilder().bootstrapStrict(raw)
        return PortalDispatcherPublication(
            table = publish(),
            registeredEntries = count,
        )
    }

    private fun startupBuilder(): PortalDispatcherTable.Builder = checkNotNull(startupBuilder) { "portal dispatcher registry is published" }

    private fun publish(): PortalDispatcherTable {
        publishedTable?.let { return it }
        val table = startupBuilder().build()
        startupBuilder = null
        publishedTable = table
        return table
    }
}

internal data class PortalDispatcherPublication(
    val table: PortalDispatcherTable,
    val registeredEntries: Int,
)

internal fun PortalDispatcherTable.Builder.bootstrapFromProperty(raw: String?): Int {
    requireActive()
    if (raw.isNullOrBlank()) return 0
    var count = 0
    for (entry in raw.split(',')) {
        runCatching { registerEntry(entry.trim(), replace = true) }
            .onSuccess { count++ }
            .onFailure { System.err.println("[PORTAL-DISPATCH] skipping '$entry': ${it.message}") }
    }
    return count
}

internal fun PortalDispatcherTable.Builder.bootstrapStrict(raw: String?): Int {
    requireActive()
    if (raw.isNullOrBlank()) return 0
    val entries = raw.split(',').map(String::trim)
    entries.forEach { registerEntry(it, replace = false) }
    return entries.size
}

private fun PortalDispatcherTable.Builder.registerEntry(
    entry: String,
    replace: Boolean,
) {
    require(entry.isNotEmpty()) { "empty entry" }
    val parts = entry.split(';')
    require(parts.size <= 2) { "expected Interface=Impl(;Dispatcher)" }
    val servicePair = parts[0].split('=')
    require(servicePair.size == 2 && servicePair.all { it.isNotBlank() }) {
        "expected Interface=Impl(;Dispatcher)"
    }
    val serviceClass = Class.forName(servicePair[0].trim())
    val impl = Class.forName(servicePair[1].trim()).getDeclaredConstructor().newInstance()

    val defaultName =
        serviceClass.packageName.let { pkg ->
            (if (pkg.isNotEmpty()) "$pkg." else "") + serviceClass.simpleName + "PortalDispatcher"
        }
    val dispatcherName = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: defaultName
    val dispatcherObject =
        Class.forName(dispatcherName).getDeclaredField("INSTANCE").get(null)
            ?: error("dispatcher $dispatcherName is not a Kotlin object")

    val methodIds = dispatcherObject.javaClass.getMethod("getMETHOD_IDS").invoke(dispatcherObject) as IntArray
    require(methodIds.isNotEmpty()) { "dispatcher $dispatcherName declares no methods" }
    val handle = dispatcherObject.javaClass.methods.single { it.name == "handle" && it.parameterCount == 4 }
    val registered: PortalDispatcherHandler = { generatedMethod, payload, granted ->
        handle.invoke(dispatcherObject, impl, generatedMethod, payload, granted) as ByteArray
    }
    if (replace) registerReplacing(methodIds, registered) else register(methodIds, registered)
}
