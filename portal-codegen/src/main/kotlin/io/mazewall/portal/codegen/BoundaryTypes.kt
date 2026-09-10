package io.mazewall.portal.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.asClassName
import java.lang.reflect.Modifier
import java.lang.reflect.RecordComponent

internal object BoundaryTypes {
    const val READ_FD: String = "io.mazewall.portal.Capability\$ReadFd"

    private val scalarKinds: Map<Class<*>, Kind> =
        mapOf(
            Void.TYPE to Kind.UnitT,
            Void::class.java to Kind.UnitT,
            java.lang.Boolean.TYPE to Kind.BooleanT,
            java.lang.Boolean::class.java to Kind.BooleanT,
            java.lang.Byte.TYPE to Kind.ByteT,
            java.lang.Byte::class.java to Kind.ByteT,
            java.lang.Short.TYPE to Kind.ShortT,
            java.lang.Short::class.java to Kind.ShortT,
            java.lang.Integer.TYPE to Kind.IntT,
            java.lang.Integer::class.java to Kind.IntT,
            java.lang.Long.TYPE to Kind.LongT,
            java.lang.Long::class.java to Kind.LongT,
            java.lang.Float.TYPE to Kind.FloatT,
            java.lang.Float::class.java to Kind.FloatT,
            java.lang.Double.TYPE to Kind.DoubleT,
            java.lang.Double::class.java to Kind.DoubleT,
            Character.TYPE to Kind.CharT,
            Character::class.java to Kind.CharT,
            String::class.java to Kind.StringT,
            ByteArray::class.java to Kind.BytesT,
        )

    sealed interface Kind {
        data object BooleanT : Kind

        data object ByteT : Kind

        data object ShortT : Kind

        data object IntT : Kind

        data object LongT : Kind

        data object FloatT : Kind

        data object DoubleT : Kind

        data object CharT : Kind

        data object StringT : Kind

        data object BytesT : Kind

        data object ReadFd : Kind

        data object UnitT : Kind

        data class RecordT(
            val typeName: ClassName,
            val components: List<Component>,
        ) : Kind {
            init {
                require(components.isNotEmpty()) { "structured boundary type must have components" }
            }
        }

        data class Component(
            val name: String,
            val kind: Kind,
            val reader: Reader,
        )

        sealed interface Reader {
            val name: String

            data class Field(
                override val name: String,
            ) : Reader

            data class Method(
                override val name: String,
            ) : Reader
        }
    }

    fun kindOf(
        type: Class<*>,
        stack: MutableSet<String> = mutableSetOf(),
    ): Kind {
        scalarKinds[type]?.let { return it }
        if (type.name == READ_FD) return Kind.ReadFd
        require(!type.isArray) { "portal boundary forbids array type ${type.name}; only byte[] is allowed" }
        require(!type.isInterface && !type.isEnum && !type.isPrimitive) { "portal boundary forbids type ${type.name}" }
        require(!isJdkForbidden(type)) {
            "portal boundary forbids type ${type.name}; allowed: primitives, String, byte[], records/POJOs of those, Capability.ReadFd"
        }
        require(Modifier.isPublic(type.modifiers)) { "portal boundary type ${type.name} must be public" }
        require(stack.add(type.name)) { "portal boundary forbids recursive type ${type.name}" }
        return try {
            describeStructured(type, stack)
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
        stack: MutableSet<String>,
    ): Kind {
        if (type.isRecord) {
            val components =
                type.recordComponents.map { component: RecordComponent ->
                    val name = component.name
                    require(name.isNotBlank()) { "record ${type.name} has an unnamed component" }
                    require(Modifier.isPublic(component.accessor.modifiers)) {
                        "record ${type.name} component $name has no public readable accessor"
                    }
                    Kind.Component(name, kindOf(component.type, stack), Kind.Reader.Method(component.accessor.name))
                }
            require(components.isNotEmpty()) { "record ${type.name} has no components" }
            return Kind.RecordT(type.asClassName(), java.util.List.copyOf(components))
        }

        val constructors = type.constructors.filter { Modifier.isPublic(it.modifiers) && it.parameterCount > 0 }
        val constructor =
            constructors.singleOrNull()
                ?: throw IllegalArgumentException(
                    "portal boundary forbids type ${type.name}: need a java record or a single public all-args constructor",
                )
        val components =
            constructor.parameters.map { parameter ->
                val name = parameter.name
                require(!name.matches(Regex("arg\\d+"))) {
                    "portal POJO ${type.name} lost constructor parameter names; compile with -parameters or use a record"
                }
                Kind.Component(name, kindOf(parameter.type, stack), readableAccessor(type, name, parameter.type))
            }
        return Kind.RecordT(type.asClassName(), java.util.List.copyOf(components))
    }

    private fun readableAccessor(
        type: Class<*>,
        component: String,
        expectedType: Class<*>,
    ): Kind.Reader {
        val suffix = component.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val candidateNames =
            buildList {
                add(component)
                add("get$suffix")
                if (expectedType == Boolean::class.javaPrimitiveType || expectedType == Boolean::class.java) add("is$suffix")
            }
        val methods =
            type.methods.filter {
                it.name in candidateNames && it.parameterCount == 0 && Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers)
            }
        val compatible = candidateNames.firstNotNullOfOrNull { name ->
            methods.firstOrNull { it.name == name && sameBoundaryType(it.returnType, expectedType) }
        }
        if (compatible != null) return Kind.Reader.Method(compatible.name)

        val fields = type.fields.filter { it.name == component && Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
        val field = fields.firstOrNull { sameBoundaryType(it.type, expectedType) }
        if (field != null) return Kind.Reader.Field(field.name)

        require(methods.isEmpty() && fields.isEmpty()) {
            "portal POJO ${type.name} component $component needs a type-compatible reader for ${expectedType.typeName}"
        }
        throw IllegalArgumentException(
            "portal POJO ${type.name} component $component needs a public readable accessor ($component(), get$suffix(), or public field)",
        )
    }

    private fun sameBoundaryType(
        actual: Class<*>,
        expected: Class<*>,
    ): Boolean = boxed(actual) == boxed(expected)

    private fun boxed(type: Class<*>): Class<*> =
        when (type) {
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            Character.TYPE -> Character::class.java
            else -> type
        }
}
