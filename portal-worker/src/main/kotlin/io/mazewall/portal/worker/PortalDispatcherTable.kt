package io.mazewall.portal.worker

import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.portal.Capability
import io.mazewall.portal.PortalMethod
import java.util.LinkedHashMap

public typealias PortalDispatcherHandler =
    (
        method: PortalMethod.Generated,
        payload: ByteArray,
        granted: List<Capability.ReadFd>,
    ) -> ByteArray

/** Immutable generated-method dispatch authority published after worker startup. */
public class PortalDispatcherTable private constructor(
    private val handlers: Map<PortalMethod.Generated, PortalDispatcherHandler>,
) {
    /** Attempts generated dispatch; null when no handler claims [method]. */
    public fun dispatchOrNull(
        method: PortalMethod.Generated,
        payload: ByteArray,
        granted: List<Capability.ReadFd>,
    ): ByteArray? = handlers[method]?.invoke(method, payload, granted)

    /** Mutable startup-only collector. [build] permanently invalidates this builder. */
    public class Builder {
        private val handlers = LinkedHashMap<PortalMethod.Generated, PortalDispatcherHandler>()
        private var published: Boolean = false

        public fun register(
            ids: IntArray,
            handler: PortalDispatcherHandler,
        ): Builder {
            val target = activeHandlers()
            val methods = ids.map(PortalMethod::Generated)
            val batch = HashSet<PortalMethod.Generated>(methods.size)
            methods.forEach { method ->
                check(batch.add(method) && !target.containsKey(method)) {
                    "duplicate portal method id: ${method.wire}"
                }
            }
            methods.forEach { method -> target[method] = handler }
            return this
        }

        internal fun registerReplacing(
            ids: IntArray,
            handler: PortalDispatcherHandler,
        ): Builder {
            val target = activeHandlers()
            ids.map { PortalMethod.Generated(it) }.forEach { method -> target[method] = handler }
            return this
        }

        internal fun requireActive() {
            activeHandlers()
        }

        public fun build(): PortalDispatcherTable {
            val target = activeHandlers()
            val table = PortalDispatcherTable(java.util.Map.copyOf(target))
            published = true
            target.clear()
            return table
        }

        private fun activeHandlers(): LinkedHashMap<PortalMethod.Generated, PortalDispatcherHandler> = handlers.also { check(!published) { "portal dispatcher table builder is already published" } }
    }
}
