package io.mazewall.portal.codegen

import java.lang.reflect.Modifier
import java.lang.reflect.RecordComponent

internal object BoundaryTypes {
    const val READ_FD: String = "io.mazewall.portal.Capability\$ReadFd"

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
            val fqcn: String,
            val components: List<Component>,
        ) : Kind

        data class Component(
            val name: String,
            val kind: Kind,
        )
    }

    fun kindOf(
        type: Class<*>,
        stack: MutableSet<String> = mutableSetOf(),
    ): Kind {
        if (type == Void.TYPE || type == Void::class.java) return Kind.UnitT
        if (type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.java) return Kind.BooleanT
        if (type == java.lang.Byte.TYPE || type == java.lang.Byte::class.java) return Kind.ByteT
        if (type == java.lang.Short.TYPE || type == java.lang.Short::class.java) return Kind.ShortT
        if (type == java.lang.Integer.TYPE || type == java.lang.Integer::class.java) return Kind.IntT
        if (type == java.lang.Long.TYPE || type == java.lang.Long::class.java) return Kind.LongT
        if (type == java.lang.Float.TYPE || type == java.lang.Float::class.java) return Kind.FloatT
        if (type == java.lang.Double.TYPE || type == java.lang.Double::class.java) return Kind.DoubleT
        if (type == Character.TYPE || type == Character::class.java) return Kind.CharT
        if (type == String::class.java) return Kind.StringT
        if (type == ByteArray::class.java) return Kind.BytesT
        if (type.name == READ_FD) return Kind.ReadFd
        if (type.isArray) {
            throw IllegalArgumentException("portal boundary forbids array type ${type.name}; only byte[] is allowed")
        }
        if (type.isInterface || type.isEnum || type.isPrimitive) {
            throw IllegalArgumentException("portal boundary forbids type ${type.name}")
        }
        if (isJdkForbidden(type)) {
            throw IllegalArgumentException(
                "portal boundary forbids type ${type.name}; allowed: primitives, String, byte[], records/POJOs of those, Capability.ReadFd",
            )
        }
        if (!stack.add(type.name)) {
            throw IllegalArgumentException("portal boundary forbids recursive type ${type.name}")
        }
        return try {
            describeStructured(type, stack)
        } finally {
            stack.remove(type.name)
        }
    }

    private fun isJdkForbidden(type: Class<*>): Boolean {
        val n = type.name
        return n.startsWith("java.io.") ||
            n.startsWith("java.nio.") ||
            n.startsWith("java.net.") ||
            n.startsWith("java.util.") ||
            n.startsWith("javax.") ||
            n.startsWith("kotlin.jvm.functions.") ||
            n == "java.lang.Object" ||
            n == "java.lang.Class"
    }

    private fun describeStructured(
        type: Class<*>,
        stack: MutableSet<String>,
    ): Kind {
        if (type.isRecord) {
            val components =
                type.recordComponents.map { rc: RecordComponent ->
                    if (rc.name.isNullOrBlank()) {
                        throw IllegalArgumentException("record ${type.name} has an unnamed component")
                    }
                    Kind.Component(rc.name, kindOf(rc.type, stack))
                }
            if (components.isEmpty()) {
                throw IllegalArgumentException("record ${type.name} has no components")
            }
            return Kind.RecordT(type.name, components)
        }
        val ctors = type.constructors.filter { Modifier.isPublic(it.modifiers) && it.parameterCount > 0 }
        val ctor =
            ctors.singleOrNull()
                ?: throw IllegalArgumentException(
                    "portal boundary forbids type ${type.name}: need a java record or a single public all-args constructor",
                )
        val components =
            ctor.parameters.map { p ->
                val name = p.name
                if (name.matches(Regex("arg\\d+"))) {
                    throw IllegalArgumentException(
                        "portal POJO ${type.name} lost constructor parameter names; compile with -parameters or use a record",
                    )
                }
                Kind.Component(name, kindOf(p.type, stack))
            }
        return Kind.RecordT(type.name, components)
    }
}
