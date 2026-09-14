@file:Suppress("ForbiddenImport") // Worker construction deliberately inspects verified guest constructors.

package io.mazewall.portal.worker

import java.lang.reflect.Constructor
import java.lang.reflect.Modifier

/** Builds an approved, worker-local constructor graph once for an isolation group. */
public class PortalWorkerConstructionContext internal constructor(
    private val loader: ClassLoader,
) {
    private val instances = mutableMapOf<Class<*>, Any>()
    private val constructing = ArrayDeque<Class<*>>()

    public fun construct(implementationName: String): Any = construct(loadClass(implementationName))

    public fun construct(type: Class<*>): Any =
        instances.getOrPut(type) {
            require(constructing.add(type)) {
                "portal worker constructor cycle: ${(constructing + type).joinToString(" -> ") { it.name }}"
            }
            try {
                val constructor = constructorFor(type)
                val arguments = constructor.parameterTypes.map(::construct).toTypedArray()
                @Suppress("SpreadOperator") // java.lang.reflect.Constructor exposes only the vararg invocation API.
                constructor.newInstance(*arguments)
            } finally {
                check(constructing.removeLast() == type)
            }
        }

    private fun loadClass(name: String): Class<*> =
        try {
            Class.forName(name, true, loader)
        } catch (failure: ClassNotFoundException) {
            throw IllegalArgumentException("portal worker cannot load collaborator $name", failure)
        }

    private fun constructorFor(type: Class<*>): Constructor<*> {
        require(!type.isInterface && !Modifier.isAbstract(type.modifiers)) {
            "portal worker has no concrete binding for collaborator ${type.name}"
        }
        require(!type.name.startsWith("java.") && !type.name.startsWith("kotlin.")) {
            "portal worker refuses to auto-construct platform collaborator ${type.name}; configure a worker factory"
        }
        val constructors = type.constructors.filter { Modifier.isPublic(it.modifiers) }
        return constructors.singleOrNull()
            ?: throw IllegalArgumentException(
                "portal worker collaborator ${type.name} needs exactly one public constructor; configure a worker factory",
            )
    }
}

/** The generated-manifest default: recursively build public worker-local collaborators. */
internal class PortalReflectiveWorkerFactory : PortalWorkerFactory {
    override fun create(manifest: io.mazewall.portal.PortalServiceManifest): Any = throw UnsupportedOperationException("reflective worker factory requires a construction context")

    override fun create(
        manifest: io.mazewall.portal.PortalServiceManifest,
        context: PortalWorkerConstructionContext,
    ): Any = context.construct(manifest.implementationName)
}
