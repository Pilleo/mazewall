@file:Suppress("ForbiddenImport") // Contract analysis intentionally uses Java reflection metadata.

package io.mazewall.portal.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.asClassName
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.RecordComponent
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.jvm.kotlinFunction

internal object BoundaryTypes {
    const val READ_FD: String = "io.mazewall.portal.Capability\$ReadFd"

    private val scalarValueKinds: Map<Class<*>?, Kind.Value> =
        mapOf(
            Boolean::class.javaPrimitiveType to Kind.BooleanT,
            Boolean::class.javaObjectType to Kind.BooleanT,
            Byte::class.javaPrimitiveType to Kind.ByteT,
            Byte::class.javaObjectType to Kind.ByteT,
            Short::class.javaPrimitiveType to Kind.ShortT,
            Short::class.javaObjectType to Kind.ShortT,
            Int::class.javaPrimitiveType to Kind.IntT,
            Int::class.javaObjectType to Kind.IntT,
            Long::class.javaPrimitiveType to Kind.LongT,
            Long::class.javaObjectType to Kind.LongT,
            Float::class.javaPrimitiveType to Kind.FloatT,
            Float::class.javaObjectType to Kind.FloatT,
            Double::class.javaPrimitiveType to Kind.DoubleT,
            Double::class.javaObjectType to Kind.DoubleT,
            Char::class.javaPrimitiveType to Kind.CharT,
            Char::class.javaObjectType to Kind.CharT,
            String::class.java to Kind.StringT,
            ByteArray::class.java to Kind.BytesT,
            java.util.UUID::class.java to Kind.UuidT,
            java.time.Instant::class.java to Kind.InstantT,
            java.math.BigDecimal::class.java to Kind.BigDecimalT,
        )

    sealed interface Kind {
        sealed interface Value :
            Kind,
            Parameter,
            Result

        sealed interface Parameter : Kind

        sealed interface Result : Kind

        data object BooleanT : Value

        data object ByteT : Value

        data object ShortT : Value

        data object IntT : Value

        data object LongT : Value

        data object FloatT : Value

        data object DoubleT : Value

        data object CharT : Value

        data object StringT : Value

        data object BytesT : Value

        data object UuidT : Value

        data object InstantT : Value

        data object BigDecimalT : Value

        data class EnumT(
            val typeName: ClassName,
        ) : Value

        data object ReadFd : Parameter

        data class InputSnapshot(
            val maximumBytes: Int,
        ) : Parameter {
            init {
                require(maximumBytes >= 0) { "portal input snapshot maximumBytes must not be negative" }
            }
        }

        data object UnitT : Result

        data class RecordT(
            val typeName: ClassName,
            val components: List<Component>,
        ) : Value {
            init {
                require(components.isNotEmpty()) { "structured boundary type must have components" }
            }
        }

        data class ListT(
            val element: Value,
        ) : Value

        data class MapT(
            val key: Value,
            val value: Value,
        ) : Value

        data class Component(
            val name: String,
            val kind: Value,
            val reader: Reader,
            val genericType: Type? = null,
        )

        sealed interface Reader {
            val name: String

            data class KotlinProperty(
                override val name: String,
            ) : Reader

            data class Field(
                override val name: String,
            ) : Reader

            data class Method(
                override val name: String,
            ) : Reader
        }
    }

    data class PathContext(
        val service: String,
        val method: String,
        val position: String,
        val fields: List<String> = emptyList(),
    ) {
        fun field(name: String): PathContext = copy(fields = fields + name)

        fun element(): PathContext = copy(fields = fields + "[]")

        fun mapKey(): PathContext = copy(fields = fields + "{key}")

        fun mapValue(): PathContext = copy(fields = fields + "{value}")

        val path: String
            get() {
                if (method.isEmpty() && position.isEmpty()) {
                    return if (fields.isEmpty()) service else "$service." + fields.joinToString(".")
                }
                val fieldSuffix = if (fields.isEmpty()) "" else "." + fields.joinToString(".")
                val descFieldSuffix = if (fields.isEmpty()) "" else " field " + fields.joinToString(".")
                val dotPath = "$service.$method.$position$fieldSuffix"
                val descPath = "$service.$method $position$descFieldSuffix"
                return if (position.startsWith("parameter ")) {
                    val paramName = position.removePrefix("parameter ")
                    "$service.$method.$paramName$fieldSuffix ($descPath)"
                } else {
                    "$dotPath ($descPath)"
                }
            }

        override fun toString(): String = path

        companion object {
            fun parameter(
                service: String,
                method: String,
                parameter: String,
            ): PathContext = PathContext(service, method, "parameter $parameter")

            fun returnType(
                service: String,
                method: String,
            ): PathContext = PathContext(service, method, "return")

            fun unspecified(label: String): PathContext = PathContext(label, "", "")
        }
    }

    fun kindOfParameter(
        type: Type,
        context: PathContext,
        snapshot: io.mazewall.portal.PortalInputSnapshotAdapter? = null,
    ): Kind.Parameter {
        if (snapshot != null) {
            require(type == java.io.InputStream::class.java) {
                "portal boundary ${context.path}: portal input snapshot adapter is supported only on an InputStream parameter"
            }
            return Kind.InputSnapshot(snapshot.maximumBytes)
        }
        if (type is Class<*> && type.name == READ_FD) {
            return Kind.ReadFd
        }
        return kindOfValue(type, context)
    }

    fun kindOfResult(
        type: Type,
        context: PathContext,
    ): Kind.Result {
        if (type == Void.TYPE || type == Void::class.java) {
            return Kind.UnitT
        }
        require(type !is Class<*> || type.name != READ_FD) {
            "portal boundary ${context.path}: Capability.ReadFd is supported only as a direct inbound parameter"
        }
        return kindOfValue(type, context)
    }

    fun kindOfValue(
        type: Type,
        context: PathContext = PathContext.unspecified(type.typeName),
        stack: MutableSet<String> = mutableSetOf(),
    ): Kind.Value =
        when (type) {
            is Class<*> -> classValueKind(type, context, stack)
            is ParameterizedType -> parameterizedValueKind(type, context, stack)
            is TypeVariable<*> -> throw IllegalArgumentException("portal boundary ${context.path}: unresolved generic type variable $type")
            else -> throw IllegalArgumentException("portal boundary ${context.path}: forbids unresolved generic type $type")
        }

    fun kindOf(
        type: Type,
        stack: MutableSet<String> = mutableSetOf(),
    ): Kind =
        if (type == Void.TYPE || type == Void::class.java) {
            Kind.UnitT
        } else if (type is Class<*> && type.name == READ_FD) {
            Kind.ReadFd
        } else {
            kindOfValue(type, PathContext.unspecified(type.typeName), stack)
        }

    fun kindOf(
        type: Class<*>,
        stack: MutableSet<String> = mutableSetOf(),
    ): Kind = kindOf(type as Type, stack)

    private fun classValueKind(
        type: Class<*>,
        context: PathContext,
        stack: MutableSet<String>,
    ): Kind.Value {
        require(type != Void.TYPE && type != Void::class.java) {
            "portal boundary ${context.path}: Unit/Void is supported only as a method return type"
        }
        require(type.name != READ_FD) {
            "portal boundary ${context.path}: Capability.ReadFd is supported only as a direct inbound parameter"
        }
        scalarValueKinds[type]?.let { return it }
        return when {
            type.isEnum -> Kind.EnumT(type.asClassName())
            else -> structuredKind(type, context, stack)
        }
    }

    private fun parameterizedValueKind(
        type: ParameterizedType,
        context: PathContext,
        stack: MutableSet<String>,
    ): Kind.Value {
        val raw = type.rawType as? Class<*> ?: throw IllegalArgumentException(
            "portal boundary ${context.path}: raw generic type $type is not a class",
        )
        val arguments = type.actualTypeArguments
        return when (raw) {
            java.util.List::class.java -> {
                require(arguments.size == 1) { "portal boundary ${context.path}: list $type must have exactly one element type" }
                Kind.ListT(kindOfValue(arguments.single(), context.element(), stack))
            }
            java.util.Map::class.java -> {
                require(arguments.size == 2) { "portal boundary ${context.path}: map $type must have key and value types" }
                Kind.MapT(
                    kindOfValue(arguments[0], context.mapKey(), stack),
                    kindOfValue(arguments[1], context.mapValue(), stack),
                )
            }
            else -> throw IllegalArgumentException(
                "portal boundary ${context.path}: forbids generic type $type; only List<T> and Map<K,V> are supported",
            )
        }
    }

    private fun structuredKind(
        type: Class<*>,
        context: PathContext,
        stack: MutableSet<String>,
    ): Kind.Value {
        require(!type.isArray) { "portal boundary ${context.path}: forbids array type ${type.name}; only byte[] is allowed" }
        require(!type.isInterface && !type.isPrimitive) { "portal boundary ${context.path}: forbids type ${type.name}" }
        require(!Modifier.isAbstract(type.modifiers)) { "portal boundary ${context.path}: abstract DTO ${type.name} is not constructible" }
        require(!isJdkForbidden(type)) {
            "portal boundary ${context.path}: forbids type ${type.name}; " +
                "allowed: primitives, String, byte[], records/POJOs of those, Capability.ReadFd"
        }
        require(Modifier.isPublic(type.modifiers)) { "portal boundary ${context.path}: type ${type.name} must be public" }
        require(!type.isLocalClass && !type.isAnonymousClass) {
            "portal boundary ${context.path}: type ${type.name} must be a top-level or static nested class"
        }
        require(!type.isMemberClass || Modifier.isStatic(type.modifiers)) {
            "portal boundary ${context.path}: member type ${type.name} must be static"
        }
        var enclosing = type.enclosingClass
        while (enclosing != null) {
            require(Modifier.isPublic(enclosing.modifiers)) {
                "portal boundary ${context.path}: type ${type.name} enclosing class ${enclosing.name} must be public"
            }
            enclosing = enclosing.enclosingClass
        }
        require(stack.add(type.name)) { "portal boundary ${context.path}: forbids recursive type ${type.name}" }
        return try {
            describeStructured(type, context, stack)
        } finally {
            stack.remove(type.name)
        }
    }

    private fun isJdkForbidden(type: Class<*>): Boolean {
        val name = type.name
        return name.startsWith("java.io.") ||
            name.startsWith("java.nio.") ||
            name.startsWith("java.net.") ||
            name.startsWith("java.util.") ||
            name.startsWith("javax.") ||
            name.startsWith("kotlin.jvm.functions.") ||
            name == "java.lang.Object" ||
            name == "java.lang.Class"
    }

    private fun describeStructured(
        type: Class<*>,
        context: PathContext,
        stack: MutableSet<String>,
    ): Kind.Value =
        if (type.isRecord) {
            describeRecord(type, context, stack)
        } else {
            describePojo(type, context, stack)
        }

    private fun describeRecord(
        type: Class<*>,
        context: PathContext,
        stack: MutableSet<String>,
    ): Kind.Value {
        val constructor = findRecordCanonicalConstructor(type)
        require(constructor != null && Modifier.isPublic(constructor.modifiers)) {
            "portal boundary ${context.path}: record ${type.name} must have a public canonical constructor"
        }
        val kFunction = constructor.kotlinFunction
        val kParams = kFunction?.parameters?.filter { it.kind == KParameter.Kind.VALUE }
        val components =
            type.recordComponents.mapIndexed { index, component: RecordComponent ->
                val name = component.name
                require(name.isNotBlank()) { "record ${type.name} has an unnamed component" }
                require(Modifier.isPublic(component.accessor.modifiers)) {
                    "portal boundary ${context.path}: record ${type.name} component $name has no public readable accessor"
                }
                val fieldContext = context.field(name)
                val isNullable = isRecordComponentNullable(type, component, kParams?.getOrNull(index))
                require(!isNullable) {
                    "portal boundary ${fieldContext.path}: nullable DTO components are not supported"
                }
                val reader = if (type.getAnnotation(Metadata::class.java) != null) {
                    Kind.Reader.KotlinProperty(name)
                } else {
                    Kind.Reader.Method(component.accessor.name)
                }
                val componentKind = kindOfValue(component.genericType, fieldContext, stack)
                Kind.Component(name, componentKind, reader, component.genericType)
            }
        require(components.isNotEmpty()) { "record ${type.name} has no components" }
        return Kind.RecordT(type.asClassName(), java.util.List.copyOf(components))
    }

    private fun findRecordCanonicalConstructor(type: Class<*>): Constructor<*>? {
        val expectedTypes = type.recordComponents.map { it.type }
        return type.declaredConstructors.firstOrNull { it.parameterTypes.toList() == expectedTypes }
    }

    private fun isRecordComponentNullable(
        type: Class<*>,
        component: RecordComponent,
        kParam: KParameter?,
    ): Boolean {
        val field = runCatching { type.getDeclaredField(component.name) }.getOrNull()
        return kParam?.type?.let(::containsNullableType) == true ||
            component.annotations.any(::isNullableAnnotation) ||
            component.accessor.annotations.any(::isNullableAnnotation) ||
            field?.annotations?.any(::isNullableAnnotation) == true
    }

    private fun describePojo(
        type: Class<*>,
        context: PathContext,
        stack: MutableSet<String>,
    ): Kind.Value {
        val constructors = type.constructors.filter { Modifier.isPublic(it.modifiers) && it.parameterCount > 0 }
        val constructor =
            constructors.singleOrNull()
                ?: throw IllegalArgumentException(
                    "portal boundary ${context.path}: forbids type ${type.name}: need a java record or a single public all-args constructor",
                )
        val kFunction = constructor.kotlinFunction
        val kParams = kFunction?.parameters?.filter { it.kind == KParameter.Kind.VALUE }
        val genericParamTypes = constructor.genericParameterTypes
        val components =
            constructor.parameters.mapIndexed { index, parameter ->
                val name = parameter.name
                require(!name.matches(Regex("arg\\d+"))) {
                    "portal POJO ${type.name} lost constructor parameter names; compile with -parameters or use a record"
                }
                val genericType = genericParamTypes[index]
                val fieldContext = context.field(name)
                val isNullable = isPojoComponentNullable(type, parameter, name, kParams?.getOrNull(index))
                require(!isNullable) {
                    "portal boundary ${fieldContext.path}: nullable DTO components are not supported"
                }
                if (type.getAnnotation(Metadata::class.java) != null) {
                    require(
                        type.declaredFields.any {
                            it.name == name && !Modifier.isStatic(it.modifiers) && sameGenericBoundaryType(it.genericType, genericType)
                        },
                    ) {
                        "portal boundary ${fieldContext.path}: portal Kotlin component ${type.name}.$name " +
                            "must be a constructor-backed property; computed and delegated readers cannot reconstruct evidence"
                    }
                }
                val reader = readableAccessor(type, name, genericType)
                val componentKind = kindOfValue(genericType, fieldContext, stack)
                Kind.Component(name, componentKind, reader, genericType)
            }
        return Kind.RecordT(type.asClassName(), java.util.List.copyOf(components))
    }

    private fun isPojoComponentNullable(
        type: Class<*>,
        parameter: java.lang.reflect.Parameter,
        name: String,
        kParam: KParameter?,
    ): Boolean {
        val field = runCatching { type.getDeclaredField(name) }.getOrNull()
        val suffix = name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val candidateNames = listOf(name, "get$suffix", "is$suffix")
        val getter = type.methods.firstOrNull { it.name in candidateNames && it.parameterCount == 0 }
        return kParam?.type?.let(::containsNullableType) == true ||
            parameter.annotations.any(::isNullableAnnotation) ||
            field?.annotations?.any(::isNullableAnnotation) == true ||
            getter?.annotations?.any(::isNullableAnnotation) == true ||
            getter?.kotlinFunction?.returnType?.let(::containsNullableType) == true
    }

    private fun containsNullableType(type: KType): Boolean = type.isMarkedNullable || type.arguments.any { it.type?.let(::containsNullableType) == true }

    private fun isNullableAnnotation(annotation: Annotation): Boolean = annotation.annotationClass.java.simpleName == "Nullable"

    private fun isBooleanType(type: Type): Boolean =
        type == Boolean::class.javaPrimitiveType ||
            type == Boolean::class.javaObjectType ||
            type == java.lang.Boolean.TYPE ||
            type == java.lang.Boolean::class.java

    private fun candidateMethodNames(
        component: String,
        expectedType: Type,
    ): List<String> {
        val suffix = component.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        return if (isBooleanType(expectedType)) {
            listOf(component, "get$suffix", "is$suffix")
        } else {
            listOf(component, "get$suffix")
        }
    }

    private fun readableAccessor(
        type: Class<*>,
        component: String,
        expectedType: Type,
    ): Kind.Reader {
        val candidateNames = candidateMethodNames(component, expectedType)
        val methods =
            type.methods.filter {
                it.name in candidateNames && it.parameterCount == 0 && Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers)
            }
        val compatible = candidateNames.firstNotNullOfOrNull { name ->
            methods.firstOrNull { it.name == name && sameGenericBoundaryType(it.genericReturnType, expectedType) }
        }
        if (compatible != null) {
            return if (isKotlinPropertyAccessor(type, component, expectedType, compatible)) {
                Kind.Reader.KotlinProperty(component)
            } else {
                Kind.Reader.Method(compatible.name)
            }
        }

        val fields = type.fields.filter { it.name == component && Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
        val field = fields.firstOrNull { sameGenericBoundaryType(it.genericType, expectedType) }
        if (field != null) return Kind.Reader.Field(field.name)

        val suffix = component.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        require(methods.isEmpty() && fields.isEmpty()) {
            "portal POJO ${type.name} component $component needs a type-compatible reader for ${expectedType.typeName}"
        }
        throw IllegalArgumentException(
            "portal POJO ${type.name} component $component needs a public readable accessor ($component(), get$suffix(), or public field)",
        )
    }

    private fun isKotlinPropertyAccessor(
        type: Class<*>,
        component: String,
        expectedType: Type,
        accessor: java.lang.reflect.Method,
    ): Boolean {
        if (type.getAnnotation(Metadata::class.java) == null) return false
        val hasCompatibleBackingField =
            type.declaredFields.any {
                it.name == component && !Modifier.isStatic(it.modifiers) && sameGenericBoundaryType(it.genericType, expectedType)
            }
        if (!hasCompatibleBackingField) return false
        val suffix = component.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val booleanIsProperty = isBooleanType(expectedType) && component.startsWith("is") && accessor.name == component
        return accessor.name == "get$suffix" || booleanIsProperty
    }

    private fun sameGenericBoundaryType(
        actual: Type,
        expected: Type,
    ): Boolean =
        when {
            actual is Class<*> && expected is Class<*> -> sameBoundaryType(actual, expected)
            actual is ParameterizedType && expected is ParameterizedType -> sameParameterizedType(actual, expected)
            actual is ParameterizedType && expected is Class<*> -> {
                val actualRaw = actual.rawType as? Class<*>
                actualRaw != null && sameBoundaryType(actualRaw, expected)
            }
            actual is Class<*> && expected is ParameterizedType -> {
                val expectedRaw = expected.rawType as? Class<*>
                expectedRaw != null && sameBoundaryType(actual, expectedRaw)
            }
            else -> actual == expected
        }

    private fun sameParameterizedType(
        actual: ParameterizedType,
        expected: ParameterizedType,
    ): Boolean {
        val actualRaw = actual.rawType as? Class<*>
        val expectedRaw = expected.rawType as? Class<*>
        val actualArgs = actual.actualTypeArguments
        val expectedArgs = expected.actualTypeArguments
        return actualRaw != null &&
            expectedRaw != null &&
            sameBoundaryType(actualRaw, expectedRaw) &&
            actualArgs.size == expectedArgs.size &&
            actualArgs.indices.all { sameGenericBoundaryType(actualArgs[it], expectedArgs[it]) }
    }

    private fun sameBoundaryType(
        actual: Class<*>,
        expected: Class<*>,
    ): Boolean = boxed(actual) == boxed(expected)

    private fun boxed(type: Class<*>): Class<*> = if (type.isPrimitive) type.kotlin.javaObjectType else type
}
