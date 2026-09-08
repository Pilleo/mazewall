package io.mazewall.portal.worker

import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.portal.PortalMethod
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry bridging generated `<Service>PortalDispatcher` objects into the worker
 * loop. Builtins are dispatched first; generated [PortalMethod.Generated] values are looked
 * up here, so a generated service invocation reaches its generated dispatcher
 * instead of falling into `unknown method`.
 *
 * Bootstrap: set `-Dio.mazewall.portal.worker.dispatchers` to a comma-separated
 * list of `fqcn.Service=fqcn.ServiceImpl[;fqcn.DispatcherObject]` entries. The
 * dispatcher object defaults to `<simple-name>PortalDispatcher` in the service's
 * package and must expose `METHOD_IDS: IntArray` plus
 * `handle(impl, method, payload, granted): ByteArray`.
 */
public object PortalDispatcherRegistry {
    public typealias Handler =
        (
            method: PortalMethod.Generated,
            payload: ByteArray,
            granted: List<FileDescriptor<FileDescriptorRole.Granted, FdState.Open, FdOwnership>>,
        ) -> ByteArray

    private val handlers = ConcurrentHashMap<PortalMethod.Generated, Handler>()

    public fun register(
        ids: IntArray,
        handler: Handler,
    ) {
        for (id in ids) handlers[PortalMethod.Generated(id)] = handler
    }

    /** Attempts registered dispatch; null when no handler claims [method]. */
    public fun dispatchOrNull(
        method: PortalMethod.Generated,
        payload: ByteArray,
        granted: List<FileDescriptor<FileDescriptorRole.Granted, FdState.Open, FdOwnership>>,
    ): ByteArray? = handlers[method]?.invoke(method, payload, granted)

    /**
     * Reflectively wires `Interface=Impl(;Dispatcher)?` pairs. Tolerant of malformed
     * entries so one bad flag cannot take down the worker before policy
     * installation - each failure logs and skips.
     */
    public fun bootstrapFromProperty(raw: String?): Int {
        if (raw.isNullOrBlank()) return 0
        var count = 0
        for (entry in raw.split(',')) {
            runCatching { registerEntry(entry.trim()) }
                .onSuccess { count++ }
                .onFailure { System.err.println("[PORTAL-DISPATCH] skipping '$entry': ${it.message}") }
        }
        return count
    }

    private fun registerEntry(entry: String) {
        require(entry.isNotEmpty()) { "empty entry" }
        val parts = entry.split(';')
        val servicePair = parts[0].split('=')
        require(servicePair.size == 2) { "expected Interface=Impl(;Dispatcher)" }
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

        val methodIds =
            dispatcherObject.javaClass.getMethod("getMETHOD_IDS").invoke(dispatcherObject) as IntArray
        require(methodIds.isNotEmpty()) { "dispatcher $dispatcherName declares no methods" }
        val handle =
            dispatcherObject.javaClass.methods.single {
                it.name == "handle" && it.parameterCount == 4
            }

        // One closure per id keeps the typed generated method exact without re-parsing.
        for (id in methodIds) {
            val method = PortalMethod.Generated(id)
            handlers[method] = { generatedMethod, payload, granted ->
                handle.invoke(dispatcherObject, impl, generatedMethod, payload, granted) as ByteArray
            }
        }
    }
}
