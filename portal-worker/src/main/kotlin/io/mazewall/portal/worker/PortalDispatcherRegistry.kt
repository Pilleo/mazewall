package io.mazewall.portal.worker

import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.portal.Capability
import io.mazewall.portal.PortalGeneratedNames
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
        granted: List<Capability.ReadFd>,
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
        // Compatibility bootstrap is tolerant of malformed entries, but it
        // must never overwrite an already-registered method ID.
        runCatching { registerEntry(entry.trim(), replace = false) }
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

internal fun PortalDispatcherTable.Builder.registerGeneratedEntry(
    entry: String,
    loader: ClassLoader,
) {
    registerEntry(entry, replace = false, loader = loader)
}

/** Registers a verified manifest against an instance already owned by its worker-local group. */
internal fun PortalDispatcherTable.Builder.registerGeneratedManifest(
    manifest: io.mazewall.portal.PortalServiceManifest,
    implementation: Any,
    loader: ClassLoader,
) {
    registerEntry(
        "${manifest.contractName}=${manifest.implementationName};${manifest.dispatcherName}",
        replace = false,
        loader = loader,
        implementation = implementation,
    )
}

private fun PortalDispatcherTable.Builder.registerEntry(
    entry: String,
    replace: Boolean,
    loader: ClassLoader = PortalDispatcherRegistry::class.java.classLoader,
    implementation: Any? = null,
) {
    require(entry.isNotEmpty()) { "empty entry" }
    val parts = entry.split(';')
    require(parts.size <= 2) { "expected Interface=Impl(;Dispatcher)" }
    val servicePair = parts[0].split('=')
    require(servicePair.size == 2 && servicePair.all { it.isNotBlank() }) {
        "expected Interface=Impl(;Dispatcher)"
    }
    val serviceClass = Class.forName(servicePair[0].trim(), false, loader)
    val impl = implementation ?: Class.forName(servicePair[1].trim(), true, loader).getDeclaredConstructor().newInstance()
    require(serviceClass.isInstance(impl)) { "implementation does not implement ${serviceClass.name}" }

    val dispatcherClass = resolveDispatcherClass(
        serviceClass,
        parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() },
        loader,
    )
    val dispatcherName = dispatcherClass.name
    val dispatcherObject =
        dispatcherClass.getDeclaredField("INSTANCE").get(null)
            ?: error("dispatcher $dispatcherName is not a Kotlin object")

    val methodIds = dispatcherObject.javaClass.getMethod("getMETHOD_IDS").invoke(dispatcherObject) as IntArray
    require(methodIds.isNotEmpty()) { "dispatcher $dispatcherName declares no methods" }
    val handle = dispatcherObject.javaClass.methods.single { it.name == "handle" && it.parameterCount == 4 }
    val registered: PortalDispatcherHandler = { generatedMethod, payload, granted ->
        handle.invoke(dispatcherObject, impl, generatedMethod, payload, granted) as ByteArray
    }
    if (replace) registerReplacing(methodIds, registered) else register(methodIds, registered)
}

private fun resolveDispatcherClass(
    serviceClass: Class<*>,
    explicitName: String?,
    loader: ClassLoader,
): Class<*> {
    if (explicitName != null) return Class.forName(explicitName, true, loader)

    val candidates = listOf(
        PortalGeneratedNames.dispatcherBinaryName(serviceClass),
        legacyNestedDispatcherBinaryName(serviceClass),
    ).distinct()
    val matches = candidates.mapNotNull { name ->
        try {
            Class.forName(name, false, loader)
        } catch (_: ClassNotFoundException) {
            null
        }
    }
    require(matches.size <= 1) {
        "multiple portal dispatchers found for ${serviceClass.name}: " +
            "${matches.joinToString { it.name }}; specify one explicitly as Interface=Impl;Dispatcher"
    }
    return matches.singleOrNull()
        ?: throw ClassNotFoundException(
            "missing portal dispatcher for ${serviceClass.name}; tried ${candidates.joinToString()}. " +
                "Regenerate portal sources or specify one explicitly as Interface=Impl;Dispatcher.",
        )
}

/** Binary name emitted for nested services before canonical nested names were introduced. */
private fun legacyNestedDispatcherBinaryName(serviceClass: Class<*>): String {
    val packageName = serviceClass.packageName
    val relativeName =
        if (packageName.isEmpty()) serviceClass.name else serviceClass.name.removePrefix("$packageName.")
    val simpleName = relativeName.replace('$', '_') + "PortalDispatcher"
    return if (packageName.isEmpty()) simpleName else "$packageName.$simpleName"
}
