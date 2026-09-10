package io.mazewall.portal.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.NameAllocator
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import java.io.File
import java.lang.reflect.Method
import java.util.Locale

public object PortalStubGenerator {
    private const val GENERATED_ID_MODULUS = 1_000_000_000
    private const val GENERATED_ID_OFFSET = 1_000
    private val processBroker = ClassName("io.mazewall.portal", "ProcessBroker")
    private val portalMethod = ClassName("io.mazewall.portal", "PortalMethod")
    private val generatedPortalMethod = portalMethod.nestedClass("Generated")
    private val portalCodec = ClassName("io.mazewall.portal", "PortalCodec")
    private val capability = ClassName("io.mazewall.portal", "Capability")
    private val readFd = capability.nestedClass("ReadFd")
    private val codecReader = portalCodec.nestedClass("Reader")

    public fun generate(service: Class<*>): List<FileSpec> {
        val schema = PortalServiceSchema.compile(service)
        return listOf(hostStub(schema), dispatcher(schema))
    }

    public fun write(
        service: Class<*>,
        stubDir: File,
        dispatcherDir: File,
    ) {
        generate(service).forEach { spec ->
            val destination = if (spec.name.endsWith("PortalDispatcher")) dispatcherDir else stubDir
            spec.writeTo(destination)
        }
    }

    internal fun methodId(
        service: Class<*>,
        method: Method,
    ): Int {
        val key = service.name + "#" + method.name + "#" + method.parameterTypes.joinToString(",") { it.name }
        return (key.hashCode() and Int.MAX_VALUE) % GENERATED_ID_MODULUS + GENERATED_ID_OFFSET
    }

    private fun hostStub(schema: PortalServiceSchema): FileSpec {
        val service = schema.service
        val stubName = service.simpleName + "PortalStub"
        val memberNames = NameAllocator()
        val brokerTag = Any()
        val brokerName = memberNames.newName("broker", brokerTag)
        val constants = constantNames(schema.methods)
        val type =
            TypeSpec
                .classBuilder(stubName)
                .addModifiers(KModifier.PUBLIC)
                .addSuperinterface(service.asClassName())
                .primaryConstructor(
                    FunSpec.constructorBuilder().addParameter(ParameterSpec.builder(brokerName, processBroker).build()).build(),
                ).addProperty(
                    PropertySpec
                        .builder(brokerName, processBroker)
                        .initializer("%N", brokerName)
                        .addModifiers(KModifier.PRIVATE)
                        .build(),
                )
        val companion = TypeSpec.companionObjectBuilder()
        schema.methods.forEach { method ->
            companion.addProperty(methodIdProperty(constants.getValue(method.method), method.id))
            type.addFunction(hostMethod(method, brokerName, constants.getValue(method.method)))
        }
        type.addType(companion.build())
        return FileSpec.builder(service.packageName, stubName).addType(type.build()).build()
    }

    private fun hostMethod(
        method: PortalMethodSchema,
        brokerName: String,
        constantName: String,
    ): FunSpec {
        val names = NameAllocator()
        val parameterNames =
            method.parameters.associateWith { parameter -> names.newName(parameter.sourceName, parameter) }
        val payloadName = names.newName("payload", HostLocal.Payload)
        val resultName = names.newName("result", HostLocal.Result)
        val readerName = names.newName("reader", HostLocal.Reader)
        val spec =
            FunSpec
                .builder(method.method.name)
                .addModifiers(KModifier.PUBLIC, KModifier.OVERRIDE)
                .returns(typeName(method.method.returnType))
        method.parameters.forEach { parameter ->
            spec.addParameter(parameterNames.getValue(parameter), typeName(parameter.type))
        }

        val payloadParts =
            method.parameters
                .filterNot { it.kind is BoundaryTypes.Kind.ReadFd }
                .map { encode(it.kind, CodeBlock.of("%N", parameterNames.getValue(it))) }
        val granted =
            method.parameters
                .filter { it.kind is BoundaryTypes.Kind.ReadFd }
                .map { CodeBlock.of("%N", parameterNames.getValue(it)) }
        val payloadExpression =
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
        spec.addStatement("val %N = %L", payloadName, payloadExpression)
        val invocation =
            CodeBlock
                .builder()
                .add("this.%N.invoke(%T.Generated(Companion.%N), %N", brokerName, portalMethod, constantName, payloadName)
                .apply { granted.forEach { add(", %L", it) } }
                .add(")")
                .build()
        if (method.returnKind is BoundaryTypes.Kind.UnitT) {
            spec.addStatement("%L", invocation)
        } else {
            spec.addStatement("val %N = %L", resultName, invocation)
            spec.addStatement("val %N = %T(%N)", readerName, codecReader, resultName)
            spec.addStatement("return %L", decode(method.returnKind, CodeBlock.of("%N", readerName)))
        }
        return spec.build()
    }

    private fun dispatcher(schema: PortalServiceSchema): FileSpec {
        val service = schema.service
        val name = service.simpleName + "PortalDispatcher"
        val constants = constantNames(schema.methods)
        val outerNames = NameAllocator()
        val implName = outerNames.newName("impl", DispatcherOuter.Impl)
        val methodName = outerNames.newName("method", DispatcherOuter.Method)
        val payloadName = outerNames.newName("payload", DispatcherOuter.Payload)
        val grantedName = outerNames.newName("granted", DispatcherOuter.Granted)
        val handle =
            FunSpec
                .builder("handle")
                .addModifiers(KModifier.PUBLIC)
                .addParameter(implName, service.asClassName())
                .addParameter(methodName, generatedPortalMethod)
                .addParameter(payloadName, ByteArray::class)
                .addParameter(grantedName, LIST.parameterizedBy(readFd))
                .returns(ByteArray::class)
                .beginControlFlow("when (%N.wire)", methodName)

        schema.methods.forEach { method ->
            val names = outerNames.copy()
            val argumentNames = method.parameters.associateWith { names.newName(it.sourceName, it) }
            val readerName = names.newName("reader", DispatcherLocal.Reader)
            val returnName = names.newName("ret", DispatcherLocal.Return)
            var descriptorIndex = 0
            handle.beginControlFlow("%N ->", constants.getValue(method.method))
            handle.addStatement("val %N = %T(%N)", readerName, codecReader, payloadName)
            method.parameters.forEach { parameter ->
                val argumentName = argumentNames.getValue(parameter)
                if (parameter.kind is BoundaryTypes.Kind.ReadFd) {
                    handle.addComment("Capability slot %L: %N", descriptorIndex, argumentName)
                    handle.addStatement("val %N = %N[%L]", argumentName, grantedName, descriptorIndex++)
                } else {
                    handle.addStatement(
                        "val %N = %L",
                        argumentName,
                        decode(parameter.kind, CodeBlock.of("%N", readerName)),
                    )
                }
            }
            val arguments = argumentNames.values.map { CodeBlock.of("%N", it) }.joinToCode()
            if (method.returnKind is BoundaryTypes.Kind.UnitT) {
                handle.addStatement("%N.%N(%L)", implName, method.method.name, arguments)
                handle.addStatement("return ByteArray(0)")
            } else {
                handle.addStatement("val %N = %N.%N(%L)", returnName, implName, method.method.name, arguments)
                handle.addStatement("return %L", encode(method.returnKind, CodeBlock.of("%N", returnName)))
            }
            handle.endControlFlow()
        }
        handle.addStatement("else -> error(%P)", "unknown generated portal method \${$methodName.wire}")
        handle.endControlFlow()

        val methodIds = schema.methods.map { CodeBlock.of("%N", constants.getValue(it.method)) }.joinToCode()
        val idsProperty =
            PropertySpec
                .builder("METHOD_IDS", IntArray::class, KModifier.PUBLIC)
                .initializer("intArrayOf(%L)", methodIds)
                .build()
        val type =
            TypeSpec
                .objectBuilder(name)
                .addModifiers(KModifier.PUBLIC)
                .apply {
                    schema.methods.forEach { addProperty(methodIdProperty(constants.getValue(it.method), it.id)) }
                }.addProperty(idsProperty)
                .addFunction(handle.build())
                .build()
        return FileSpec.builder(service.packageName, name).addType(type).build()
    }

    private fun constantNames(methods: List<PortalMethodSchema>): Map<Method, String> {
        val allocator = NameAllocator()
        return methods.associate { schema ->
            schema.method to allocator.newName(schema.method.name.uppercase(Locale.ROOT) + "_METHOD_ID", schema.method)
        }
    }

    private fun methodIdProperty(
        name: String,
        id: Int,
    ): PropertySpec = PropertySpec.builder(name, Int::class, KModifier.PRIVATE, KModifier.CONST).initializer("%L", id).build()

    private fun typeName(type: Class<*>): TypeName =
        when (type) {
            Void.TYPE, Void::class.java -> UNIT
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> Boolean::class.asTypeName()
            java.lang.Byte.TYPE, java.lang.Byte::class.java -> Byte::class.asTypeName()
            java.lang.Short.TYPE, java.lang.Short::class.java -> Short::class.asTypeName()
            java.lang.Integer.TYPE, java.lang.Integer::class.java -> Int::class.asTypeName()
            java.lang.Long.TYPE, java.lang.Long::class.java -> Long::class.asTypeName()
            java.lang.Float.TYPE, java.lang.Float::class.java -> Float::class.asTypeName()
            java.lang.Double.TYPE, java.lang.Double::class.java -> Double::class.asTypeName()
            Character.TYPE, Character::class.java -> Char::class.asTypeName()
            String::class.java -> String::class.asTypeName()
            else -> type.asTypeName()
        }

    private fun encode(
        kind: BoundaryTypes.Kind,
        expression: CodeBlock,
    ): CodeBlock =
        when (kind) {
            BoundaryTypes.Kind.BooleanT -> CodeBlock.of("%T.encodeBoolean(%L)", portalCodec, expression)
            BoundaryTypes.Kind.ByteT -> CodeBlock.of("%T.encodeByte(%L)", portalCodec, expression)
            BoundaryTypes.Kind.ShortT -> CodeBlock.of("%T.encodeShort(%L)", portalCodec, expression)
            BoundaryTypes.Kind.IntT -> CodeBlock.of("%T.encodeInt(%L)", portalCodec, expression)
            BoundaryTypes.Kind.LongT -> CodeBlock.of("%T.encodeLong(%L)", portalCodec, expression)
            BoundaryTypes.Kind.FloatT -> CodeBlock.of("%T.encodeFloat(%L)", portalCodec, expression)
            BoundaryTypes.Kind.DoubleT -> CodeBlock.of("%T.encodeDouble(%L)", portalCodec, expression)
            BoundaryTypes.Kind.CharT -> CodeBlock.of("%T.encodeChar(%L)", portalCodec, expression)
            BoundaryTypes.Kind.StringT -> CodeBlock.of("%T.encodeString(%L)", portalCodec, expression)
            BoundaryTypes.Kind.BytesT -> CodeBlock.of("%T.encodeBytes(%L)", portalCodec, expression)
            BoundaryTypes.Kind.ReadFd -> error("ReadFd is not encoded in the payload")
            BoundaryTypes.Kind.UnitT -> CodeBlock.of("ByteArray(0)")
            is BoundaryTypes.Kind.RecordT -> {
                val encoded = kind.components.map { component -> encode(component.kind, propertyRead(expression, component)) }
                CodeBlock
                    .builder()
                    .add("%T.concat(listOf(", portalCodec)
                    .add(encoded.joinToCode())
                    .add("))")
                    .build()
            }
        }

    private fun propertyRead(
        receiver: CodeBlock,
        component: BoundaryTypes.Kind.Component,
    ): CodeBlock =
        when (component.reader) {
            is BoundaryTypes.Kind.Reader.KotlinProperty -> CodeBlock.of("%L.%N", receiver, component.reader.name)
            is BoundaryTypes.Kind.Reader.Field -> CodeBlock.of("%L.%N", receiver, component.reader.name)
            is BoundaryTypes.Kind.Reader.Method -> CodeBlock.of("%L.%N()", receiver, component.reader.name)
        }

    private fun decode(
        kind: BoundaryTypes.Kind,
        reader: CodeBlock,
    ): CodeBlock =
        when (kind) {
            BoundaryTypes.Kind.BooleanT -> CodeBlock.of("%L.boolean()", reader)
            BoundaryTypes.Kind.ByteT -> CodeBlock.of("%L.byte()", reader)
            BoundaryTypes.Kind.ShortT -> CodeBlock.of("%L.short()", reader)
            BoundaryTypes.Kind.IntT -> CodeBlock.of("%L.int()", reader)
            BoundaryTypes.Kind.LongT -> CodeBlock.of("%L.long()", reader)
            BoundaryTypes.Kind.FloatT -> CodeBlock.of("%L.float()", reader)
            BoundaryTypes.Kind.DoubleT -> CodeBlock.of("%L.double()", reader)
            BoundaryTypes.Kind.CharT -> CodeBlock.of("%L.char()", reader)
            BoundaryTypes.Kind.StringT -> CodeBlock.of("%L.string()", reader)
            BoundaryTypes.Kind.BytesT -> CodeBlock.of("%L.bytes()", reader)
            BoundaryTypes.Kind.ReadFd -> error("ReadFd is taken from granted FDs")
            BoundaryTypes.Kind.UnitT -> CodeBlock.of("Unit")
            is BoundaryTypes.Kind.RecordT -> {
                CodeBlock
                    .builder()
                    .add("%T(", kind.typeName)
                    .add("%L", kind.components.map { component -> decode(component.kind, reader) }.joinToCode())
                    .add(")")
                    .build()
            }
        }

    private enum class HostLocal { Payload, Result, Reader }

    private enum class DispatcherOuter { Impl, Method, Payload, Granted }

    private enum class DispatcherLocal { Reader, Return }
}

private fun List<CodeBlock>.joinToCode(): CodeBlock {
    val builder = CodeBlock.builder()
    forEachIndexed { index, block ->
        if (index > 0) builder.add(", ")
        builder.add("%L", block)
    }
    return builder.build()
}
