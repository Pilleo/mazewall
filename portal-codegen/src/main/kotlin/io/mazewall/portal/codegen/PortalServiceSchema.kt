package io.mazewall.portal.codegen

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
                    .filterNot {
                        it.isSynthetic ||
                            it.isBridge ||
                            java.lang.reflect.Modifier
                        .isStatic(it.modifiers)
                    }.filterNot { it.declaringClass == Any::class.java }
                    .groupBy(::signature)
                    .map { (signature, candidates) -> resolveOverride(service, signature, candidates) }
                    .sortedBy(::signature)

            effective.groupBy { it.name }.forEach { (name, overloads) ->
                require(overloads.size == 1) {
                    val descriptions = overloads.map(::displaySignature).sorted().joinToString(", ")
                    "portal codegen forbids overloads of $name on ${service.name}; conflicting effective methods: $descriptions"
                }
            }

            val seenIds = linkedMapOf<Int, java.lang.reflect.Method>()
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
                                    val kind = BoundaryTypes.kindOf(parameter.type)
                                    validateCapabilityPlacement(kind, "${service.name}.${method.name}.${parameter.name}", directInbound = true)
                                    PortalParameterSchema(parameter.name, parameter.type, kind)
                                },
                            ),
                        returnKind = BoundaryTypes.kindOf(method.returnType).also {
                            validateCapabilityPlacement(it, "${service.name}.${method.name}.return", directInbound = false)
                        },
                    )
                }
            return PortalServiceSchema(service, java.util.List.copyOf(methods))
        }

        private fun validateCapabilityPlacement(
            kind: BoundaryTypes.Kind,
            path: String,
            directInbound: Boolean,
        ) {
            if (kind is BoundaryTypes.Kind.ReadFd) {
                require(directInbound) { "portal boundary $path: Capability.ReadFd is supported only as a direct inbound parameter" }
            }
            if (kind is BoundaryTypes.Kind.RecordT) {
                kind.components.forEach { validateCapabilityPlacement(it.kind, "$path.${it.name}", directInbound = false) }
            }
        }

        private fun resolveOverride(
            service: Class<*>,
            signature: String,
            candidates: List<java.lang.reflect.Method>,
        ): java.lang.reflect.Method {
            val mostSpecific =
                candidates.filter { candidate ->
                    candidates.all { other ->
                        candidate == other || other.declaringClass.isAssignableFrom(candidate.declaringClass)
                    }
                }
            val direct = candidates.firstOrNull { it.declaringClass == service }
            val identicalReturn = candidates.map(java.lang.reflect.Method::getReturnType).distinct().size == 1
            return when {
                candidates.size == 1 -> candidates.single()
                direct != null -> direct
                mostSpecific.size == 1 -> mostSpecific.single()
                identicalReturn ->
                    candidates.minWith(
                        compareBy<java.lang.reflect.Method>(
                            { it.declaringClass.name },
                            java.lang.reflect.Method::toGenericString,
                        ),
                    )
                else -> resolveCovariantOverride(service, signature, candidates)
            }
        }

        private fun resolveCovariantOverride(
            service: Class<*>,
            signature: String,
            candidates: List<java.lang.reflect.Method>,
        ): java.lang.reflect.Method {
            val covariant =
                candidates.filter { candidate ->
                    candidates.all { other -> other.returnType.isAssignableFrom(candidate.returnType) }
                }
            require(covariant.size == 1) {
                "portal service ${service.name} inherits conflicting methods for $signature: " +
                    candidates.map(::displaySignature).sorted().joinToString()
            }
            return covariant.single()
        }

        private fun signature(method: java.lang.reflect.Method): String = method.name + "(" + method.parameterTypes.joinToString(",") { it.name } + ")"

        private fun displaySignature(method: java.lang.reflect.Method): String = signature(method)
    }
}

internal data class PortalMethodSchema(
    val method: java.lang.reflect.Method,
    val id: Int,
    val parameters: List<PortalParameterSchema>,
    val returnKind: BoundaryTypes.Kind,
)

internal data class PortalParameterSchema(
    val sourceName: String,
    val type: Class<*>,
    val kind: BoundaryTypes.Kind,
)
