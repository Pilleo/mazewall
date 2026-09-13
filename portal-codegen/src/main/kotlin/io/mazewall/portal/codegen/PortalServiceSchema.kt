@file:Suppress("ForbiddenImport") // Resolving inherited Java generic signatures requires reflection metadata.

package io.mazewall.portal.codegen

import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import kotlin.reflect.KParameter
import kotlin.reflect.jvm.kotlinFunction

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

            val seenIds = linkedMapOf<Int, java.lang.reflect.Method>()
            val methods =
                effective.map { method ->
                    val nullability = kotlinNullability(service, method)
                    val resolvedTypes = resolveMethodTypes(service, method)
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
                                method.parameters.mapIndexed { index, parameter ->
                                    val snapshot = parameter.getAnnotation(io.mazewall.portal.PortalInputSnapshotAdapter::class.java)
                                    val kind =
                                        if (parameter.type == java.io.InputStream::class.java && snapshot != null) {
                                            BoundaryTypes.Kind.InputSnapshot(snapshot.maximumBytes)
                                        } else {
                                            require(snapshot == null) {
                                                "portal input snapshot adapter is supported only on an InputStream parameter: " +
                                                    "${service.name}.${method.name}.${parameter.name}"
                                            }
                                            BoundaryTypes.kindOf(resolvedTypes.parameters[index])
                                        }
                                    validateCapabilityPlacement(kind, "${service.name}.${method.name}.${parameter.name}", directInbound = true)
                                    PortalParameterSchema(parameter.name, resolvedTypes.parameters[index], kind, nullability?.parameters?.get(index) ?: false)
                                },
                            ),
                        returnType = resolvedTypes.returnType,
                        returnKind = BoundaryTypes.kindOf(resolvedTypes.returnType).also {
                            validateCapabilityPlacement(it, "${service.name}.${method.name}.return", directInbound = false)
                        },
                        returnNullable = nullability?.returnNullable ?: false,
                    )
                }
            return PortalServiceSchema(service, java.util.List.copyOf(methods))
        }

        private fun kotlinNullability(
            service: Class<*>,
            method: java.lang.reflect.Method,
        ): MethodNullability? {
            if (method.declaringClass.getAnnotation(Metadata::class.java) == null) return null
            val function =
                method.kotlinFunction
                    ?: throw IllegalArgumentException(
                        "portal service ${service.name} method ${displaySignature(method)} has Kotlin metadata " +
                            "but its nullability cannot be resolved",
                    )
            val parameters = function.parameters.filter { it.kind == KParameter.Kind.VALUE }
            require(parameters.size == method.parameterCount) {
                "portal service ${service.name} method ${displaySignature(method)} has inconsistent Kotlin parameter metadata"
            }
            return MethodNullability(
                java.util.List.copyOf(parameters.map { it.type.isMarkedNullable }),
                function.returnType.isMarkedNullable,
            )
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
            if (kind is BoundaryTypes.Kind.ListT) validateCapabilityPlacement(kind.element, "$path[]", directInbound = false)
            if (kind is BoundaryTypes.Kind.MapT) {
                validateCapabilityPlacement(kind.key, "$path{key}", directInbound = false)
                validateCapabilityPlacement(kind.value, "$path{value}", directInbound = false)
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

        private fun resolveMethodTypes(
            service: Class<*>,
            method: java.lang.reflect.Method,
        ): ResolvedMethodTypes {
            val bindings = findBindings(service, method.declaringClass)
            return ResolvedMethodTypes(
                method.genericParameterTypes.map { substitute(it, bindings) },
                substitute(method.genericReturnType, bindings),
            )
        }

        private fun findBindings(
            service: Class<*>,
            declaringClass: Class<*>,
        ): Map<TypeVariable<*>, Type> {
            if (service == declaringClass) return emptyMap()
            return findBindings(service, declaringClass, emptyMap())
                ?: throw IllegalArgumentException("portal service ${service.name} cannot resolve inherited contract ${declaringClass.name}")
        }

        private fun findBindings(
            current: Class<*>,
            target: Class<*>,
            bindings: Map<TypeVariable<*>, Type>,
        ): Map<TypeVariable<*>, Type>? {
            current.genericInterfaces.forEach { parent ->
                val raw = rawClass(parent) ?: return@forEach
                val parentBindings = bindingsFor(parent, bindings)
                if (raw == target) return parentBindings
                findBindings(raw, target, parentBindings)?.let { return it }
            }
            return null
        }

        private fun bindingsFor(
            type: Type,
            inherited: Map<TypeVariable<*>, Type>,
        ): Map<TypeVariable<*>, Type> {
            val raw = rawClass(type) ?: return emptyMap()
            val arguments = (type as? ParameterizedType)?.actualTypeArguments ?: return emptyMap()
            return raw.typeParameters.zip(arguments.map { substitute(it, inherited) }).toMap()
        }

        private fun rawClass(type: Type): Class<*>? =
            when (type) {
                is Class<*> -> type
                is ParameterizedType -> type.rawType as? Class<*>
                else -> null
            }

        private fun substitute(
            type: Type,
            bindings: Map<TypeVariable<*>, Type>,
        ): Type =
            when (type) {
                is TypeVariable<*> -> bindings[type]?.let { substitute(it, bindings) } ?: type
                is ParameterizedType -> ResolvedParameterizedType(
                    type.rawType,
                    type.actualTypeArguments.map { substitute(it, bindings) }.toTypedArray(),
                    type.ownerType?.let { substitute(it, bindings) },
                )
                is WildcardType -> {
                    require(type.lowerBounds.isEmpty() && type.upperBounds.size == 1) {
                        "portal boundary forbids wildcard type $type"
                    }
                    substitute(type.upperBounds.single(), bindings)
                }
                is GenericArrayType -> type
                else -> type
            }
    }
}

internal data class PortalMethodSchema(
    val method: java.lang.reflect.Method,
    val id: Int,
    val parameters: List<PortalParameterSchema>,
    val returnType: Type,
    val returnKind: BoundaryTypes.Kind,
    val returnNullable: Boolean,
)

private data class ResolvedMethodTypes(
    val parameters: List<Type>,
    val returnType: Type,
)

private class ResolvedParameterizedType(
    private val raw: Type,
    private val arguments: Array<Type>,
    private val owner: Type?,
) : ParameterizedType {
    override fun getActualTypeArguments(): Array<Type> = arguments.clone()

    override fun getRawType(): Type = raw

    override fun getOwnerType(): Type? = owner

    override fun getTypeName(): String = "$raw<${arguments.joinToString(",") { it.typeName }}>"
}

internal data class PortalParameterSchema(
    val sourceName: String,
    val type: java.lang.reflect.Type,
    val kind: BoundaryTypes.Kind,
    val nullable: Boolean,
)

private data class MethodNullability(
    val parameters: List<Boolean>,
    val returnNullable: Boolean,
)
