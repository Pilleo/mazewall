package io.mazewall.portal.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import java.io.File
import java.lang.reflect.Method

public object PortalStubGenerator {
    private val processBroker = ClassName("io.mazewall.portal", "ProcessBroker")
    private val portalCodec = ClassName("io.mazewall.portal", "PortalCodec")
    private val capability = ClassName("io.mazewall.portal", "Capability")
    private val readFd = capability.nestedClass("ReadFd")
    private val codecReader = portalCodec.nestedClass("Reader")

    public fun generate(service: Class<*>): List<FileSpec> {
        require(service.isInterface) { "portal codegen requires an interface, got ${service.name}" }
        val methods = service.methods.filter { it.declaringClass == service && !it.isSynthetic }
        val grouped = methods.groupBy { it.name }
        grouped.forEach { (name, overloads) ->
            require(overloads.size == 1) { "portal codegen forbids overloads of $name on ${service.name}" }
        }
        val ids = linkedMapOf<Method, Int>()
        val seen = mutableSetOf<Int>()
        for (m in methods) {
            val id = methodId(service, m)
            require(seen.add(id)) { "portal method id collision on ${service.name}.${m.name}" }
            ids[m] = id
            m.parameterTypes.forEach { BoundaryTypes.kindOf(it) }
            BoundaryTypes.kindOf(m.returnType)
        }
        return listOf(hostStub(service, ids), dispatcher(service, ids))
    }

    public fun write(
        service: Class<*>,
        stubDir: File,
        dispatcherDir: File,
    ) {
        generate(service).forEach { spec ->
            val dest = if (spec.name.endsWith("PortalDispatcher")) dispatcherDir else stubDir
            spec.writeTo(dest)
        }
    }

    internal fun methodId(
        service: Class<*>,
        method: Method,
    ): Int {
        val key = service.name + "#" + method.name + "#" + method.parameterTypes.joinToString(",") { it.name }
        return (key.hashCode() and 0x7fffffff) % 1_000_000_000 + 1_000
    }

    private fun hostStub(
        service: Class<*>,
        ids: Map<Method, Int>,
    ): FileSpec {
        val pkg = service.packageName
        val stubName = service.simpleName + "PortalStub"
        val ctorParam = ParameterSpec.builder("broker", processBroker).build()
        val type =
            TypeSpec
                .classBuilder(stubName)
                .addModifiers(KModifier.PUBLIC)
                .addSuperinterface(service.asClassName())
                .primaryConstructor(
                    FunSpec.constructorBuilder().addParameter(ctorParam).build(),
                ).addProperty(
                    PropertySpec
                        .builder("broker", processBroker)
                        .initializer("broker")
                        .addModifiers(KModifier.PRIVATE)
                        .build(),
                )
        for ((method, id) in ids) {
            type.addFunction(hostMethod(method, id))
        }
        return FileSpec.builder(pkg, stubName).addType(type.build()).build()
    }

    private fun hostMethod(
        method: Method,
        id: Int,
    ): FunSpec {
        val spec =
            FunSpec
                .builder(method.name)
                .addModifiers(KModifier.PUBLIC, KModifier.OVERRIDE)
                .returns(typeName(method.returnType))
        method.parameters.forEach { p ->
            spec.addParameter(p.name, typeName(p.type))
        }
        val payloadParts = mutableListOf<CodeBlock>()
        val granted = mutableListOf<String>()
        method.parameters.forEach { p ->
            val kind = BoundaryTypes.kindOf(p.type)
            if (kind is BoundaryTypes.Kind.ReadFd) {
                granted.add(p.name)
            } else {
                payloadParts.add(encode(kind, p.name))
            }
        }
        val payloadExpr =
            if (payloadParts.isEmpty()) {
                CodeBlock.of("ByteArray(0)")
            } else {
                CodeBlock
                    .builder()
                    .add("%T.concat(listOf(", portalCodec)
                    .add(payloadParts.joinToCode())
                    .add("))")
                    .build()
            }
        spec.addStatement("val payload = %L", payloadExpr)
        val invoke =
            if (granted.isEmpty()) {
                CodeBlock.of("broker.invoke(%L, payload)", id)
            } else {
                CodeBlock.of("broker.invoke(%L, payload, %L)", id, granted.joinToString(", "))
            }
        val ret = BoundaryTypes.kindOf(method.returnType)
        if (ret is BoundaryTypes.Kind.UnitT) {
            spec.addStatement("%L", invoke)
        } else {
            spec.addStatement("val result = %L", invoke)
            spec.addStatement("val reader = %T(result)", codecReader)
            spec.addStatement("return %L", decode(ret, "reader"))
        }
        return spec.build()
    }

    private fun typeName(type: Class<*>): com.squareup.kotlinpoet.TypeName =
        if (type == Void.TYPE || type == Void::class.java) {
            UNIT
        } else {
            type.asTypeName()
        }

    private fun dispatcher(
        service: Class<*>,
        ids: Map<Method, Int>,
    ): FileSpec {
        val pkg = service.packageName
        val name = service.simpleName + "PortalDispatcher"
        val handle =
            FunSpec
                .builder("handle")
                .addModifiers(KModifier.PUBLIC)
                .addParameter("impl", service.asClassName())
                .addParameter("methodId", Int::class)
                .addParameter("payload", ByteArray::class)
                .addParameter("granted", LIST.parameterizedBy(readFd))
                .returns(ByteArray::class)
                .beginControlFlow("when (methodId)")
        var fdIndex = 0
        for ((method, id) in ids) {
            fdIndex = 0
            handle.beginControlFlow("%L ->", id)
            handle.addStatement("val reader = %T(payload)", codecReader)
            val args = mutableListOf<String>()
            method.parameters.forEach { p ->
                val kind = BoundaryTypes.kindOf(p.type)
                if (kind is BoundaryTypes.Kind.ReadFd) {
                    handle.addStatement("val %N = granted[%L]", p.name, fdIndex)
                    fdIndex++
                    args.add(p.name)
                } else {
                    handle.addStatement("val %N = %L", p.name, decode(kind, "reader"))
                    args.add(p.name)
                }
            }
            val call = args.joinToString(", ")
            val ret = BoundaryTypes.kindOf(method.returnType)
            if (ret is BoundaryTypes.Kind.UnitT) {
                handle.addStatement("impl.%N(%L)", method.name, call)
                handle.addStatement("return ByteArray(0)")
            } else {
                handle.addStatement("val ret = impl.%N(%L)", method.name, call)
                handle.addStatement("return %L", encode(ret, "ret"))
            }
            handle.endControlFlow()
        }
        handle.addStatement("else -> error(%P)", "unknown portal method \$methodId")
        handle.endControlFlow()
        val type =
            TypeSpec
                .objectBuilder(name)
                .addModifiers(KModifier.PUBLIC)
                .addFunction(handle.build())
                .build()
        return FileSpec.builder(pkg, name).addType(type).build()
    }

    private fun encode(
        kind: BoundaryTypes.Kind,
        expr: String,
    ): CodeBlock =
        when (kind) {
            BoundaryTypes.Kind.BooleanT -> CodeBlock.of("%T.encodeBoolean(%N)", portalCodec, expr)
            BoundaryTypes.Kind.ByteT -> CodeBlock.of("%T.encodeByte(%N)", portalCodec, expr)
            BoundaryTypes.Kind.ShortT -> CodeBlock.of("%T.encodeShort(%N)", portalCodec, expr)
            BoundaryTypes.Kind.IntT -> CodeBlock.of("%T.encodeInt(%N)", portalCodec, expr)
            BoundaryTypes.Kind.LongT -> CodeBlock.of("%T.encodeLong(%N)", portalCodec, expr)
            BoundaryTypes.Kind.FloatT -> CodeBlock.of("%T.encodeFloat(%N)", portalCodec, expr)
            BoundaryTypes.Kind.DoubleT -> CodeBlock.of("%T.encodeDouble(%N)", portalCodec, expr)
            BoundaryTypes.Kind.CharT -> CodeBlock.of("%T.encodeChar(%N)", portalCodec, expr)
            BoundaryTypes.Kind.StringT -> CodeBlock.of("%T.encodeString(%N)", portalCodec, expr)
            BoundaryTypes.Kind.BytesT -> CodeBlock.of("%T.encodeBytes(%N)", portalCodec, expr)
            BoundaryTypes.Kind.ReadFd -> error("ReadFd is not encoded in the payload")
            BoundaryTypes.Kind.UnitT -> CodeBlock.of("ByteArray(0)")
            is BoundaryTypes.Kind.RecordT -> {
                val builder = CodeBlock.builder().add("%T.concat(listOf(", portalCodec)
                kind.components.forEachIndexed { i, c ->
                    if (i > 0) builder.add(", ")
                    builder.add("%L", encodeProperty(c.kind, expr, c.name))
                }
                builder.add("))")
                builder.build()
            }
        }

    private fun encodeProperty(
        kind: BoundaryTypes.Kind,
        recv: String,
        prop: String,
    ): CodeBlock {
        val access = "$recv.$prop"
        return when (kind) {
            BoundaryTypes.Kind.BooleanT -> CodeBlock.of("%T.encodeBoolean(%L)", portalCodec, access)
            BoundaryTypes.Kind.ByteT -> CodeBlock.of("%T.encodeByte(%L)", portalCodec, access)
            BoundaryTypes.Kind.ShortT -> CodeBlock.of("%T.encodeShort(%L)", portalCodec, access)
            BoundaryTypes.Kind.IntT -> CodeBlock.of("%T.encodeInt(%L)", portalCodec, access)
            BoundaryTypes.Kind.LongT -> CodeBlock.of("%T.encodeLong(%L)", portalCodec, access)
            BoundaryTypes.Kind.FloatT -> CodeBlock.of("%T.encodeFloat(%L)", portalCodec, access)
            BoundaryTypes.Kind.DoubleT -> CodeBlock.of("%T.encodeDouble(%L)", portalCodec, access)
            BoundaryTypes.Kind.CharT -> CodeBlock.of("%T.encodeChar(%L)", portalCodec, access)
            BoundaryTypes.Kind.StringT -> CodeBlock.of("%T.encodeString(%L)", portalCodec, access)
            BoundaryTypes.Kind.BytesT -> CodeBlock.of("%T.encodeBytes(%L)", portalCodec, access)
            is BoundaryTypes.Kind.RecordT -> encode(kind, access)
            else -> throw IllegalArgumentException("cannot encode property $prop of kind $kind")
        }
    }

    private fun decode(
        kind: BoundaryTypes.Kind,
        reader: String,
    ): CodeBlock =
        when (kind) {
            BoundaryTypes.Kind.BooleanT -> CodeBlock.of("%N.boolean()", reader)
            BoundaryTypes.Kind.ByteT -> CodeBlock.of("%N.byte()", reader)
            BoundaryTypes.Kind.ShortT -> CodeBlock.of("%N.short()", reader)
            BoundaryTypes.Kind.IntT -> CodeBlock.of("%N.int()", reader)
            BoundaryTypes.Kind.LongT -> CodeBlock.of("%N.long()", reader)
            BoundaryTypes.Kind.FloatT -> CodeBlock.of("%N.float()", reader)
            BoundaryTypes.Kind.DoubleT -> CodeBlock.of("%N.double()", reader)
            BoundaryTypes.Kind.CharT -> CodeBlock.of("%N.char()", reader)
            BoundaryTypes.Kind.StringT -> CodeBlock.of("%N.string()", reader)
            BoundaryTypes.Kind.BytesT -> CodeBlock.of("%N.bytes()", reader)
            BoundaryTypes.Kind.ReadFd -> error("ReadFd is taken from granted FDs")
            BoundaryTypes.Kind.UnitT -> CodeBlock.of("Unit")
            is BoundaryTypes.Kind.RecordT -> {
                val type = kind.fqcn.split('.').let { ClassName.bestGuess(kind.fqcn) }
                val builder = CodeBlock.builder().add("%T(", type)
                kind.components.forEachIndexed { i, c ->
                    if (i > 0) builder.add(", ")
                    builder.add("%L", decode(c.kind, reader))
                }
                builder.add(")")
                builder.build()
            }
        }
}

private fun List<CodeBlock>.joinToCode(): CodeBlock {
    val b = CodeBlock.builder()
    forEachIndexed { i, block ->
        if (i > 0) b.add(", ")
        b.add("%L", block)
    }
    return b.build()
}

// Silence unused import if asTypeName is not needed in some KotlinPoet versions.
