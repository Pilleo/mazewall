package io.mazewall.portal.codegen

import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class PortalServiceSchema(
    val service: Class<*>,
    val methods: List<PortalMethodSchema>,
) {
    init {
        require(service.isInterface)
        require(methods.isNotEmpty()) { "portal service ${service.name} has no callable methods" }
    }

    companion object {
        fun compile(service: Class<*>): PortalServiceSchema {
            require(service.isInterface) { "portal codegen requires an interface, got ${service.name}" }
            val effective =
                service.methods
                    .asSequence()
                    .filterNot { it.isSynthetic || it.isBridge || Modifier.isStatic(it.modifiers) }
                    .filterNot { it.declaringClass == Any::class.java }
                    .groupBy(::signature)
                    .map { (signature, candidates) -> resolveOverride(service, signature, candidates) }
                    .sortedBy(::signature)

            effective.groupBy { it.name }.forEach { (name, overloads) ->
                require(overloads.size == 1) {
                    val descriptions = overloads.map(::displaySignature).sorted().joinToString(", ")
                    "portal codegen forbids overloads of $name on ${service.name}; conflicting effective methods: $descriptions"
                }
            }

            val seenIds = linkedMapOf<Int, Method>()
            val methods =
                effective.map { method ->
                    val id = PortalStubGenerator.methodId(service, method)
                    val previous = seenIds.putIfAbsent(id, method)
                    require(previous == null) {
                        "portal method id collision on ${service.name}: ${displaySignature(checkNotNull(previous))} and " +
                            "${displaySignature(method)} both map to $id"
                    }
                    PortalMethodSchema(
                        method = method,
                        id = id,
                        parameters =
                            java.util.List.copyOf(
                                method.parameters.map { parameter ->
                                    PortalParameterSchema(parameter.name, parameter.type, BoundaryTypes.kindOf(parameter.type))
                                },
                            ),
                        returnKind = BoundaryTypes.kindOf(method.returnType),
                    )
                }
            return PortalServiceSchema(service, java.util.List.copyOf(methods))
        }

        private fun resolveOverride(
            service: Class<*>,
            signature: String,
            candidates: List<Method>,
        ): Method {
            if (candidates.size == 1) return candidates.single()
            candidates.firstOrNull { it.declaringClass == service }?.let { return it }
            val mostSpecific =
                candidates.filter { candidate ->
                    candidates.all { other ->
                        candidate == other || other.declaringClass.isAssignableFrom(candidate.declaringClass)
                    }
                }
            if (mostSpecific.size == 1) return mostSpecific.single()
            val covariant =
                candidates.filter { candidate -> candidates.all { other -> other.returnType.isAssignableFrom(candidate.returnType) } }
            require(covariant.size == 1) {
                "portal service ${service.name} inherits conflicting methods for $signature: " +
                    candidates.map(::displaySignature).sorted().joinToString()
            }
            return covariant.single()
        }

        private fun signature(method: Method): String = method.name + "(" + method.parameterTypes.joinToString(",") { it.name } + ")"

        private fun displaySignature(method: Method): String = signature(method)
    }
}

internal data class PortalMethodSchema(
    val method: Method,
    val id: Int,
    val parameters: List<PortalParameterSchema>,
    val returnKind: BoundaryTypes.Kind,
)

internal data class PortalParameterSchema(
    val sourceName: String,
    val type: Class<*>,
    val kind: BoundaryTypes.Kind,
)
