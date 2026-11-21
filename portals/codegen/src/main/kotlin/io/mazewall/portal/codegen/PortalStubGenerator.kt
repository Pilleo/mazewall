@file:Suppress("ForbiddenImport") // Source generation consumes the resolved Java contract model.

package io.mazewall.portal.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.MUTABLE_LIST
import com.squareup.kotlinpoet.MUTABLE_MAP
import com.squareup.kotlinpoet.NameAllocator
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import io.mazewall.portal.PortalGeneratedNames
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.Locale
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.jvm.kotlinFunction

public object PortalStubGenerator {
    private const val GENERATED_ID_MODULUS = 1_000_000_000
    private const val GENERATED_ID_OFFSET = 1_000
    private val processBroker = ClassName("io.mazewall.portal", "ProcessBroker")
    private val portalMethod = ClassName("io.mazewall.portal", "PortalMethod")
    private val generatedPortalMethod = portalMethod.nestedClass("Generated")
    private val portalCodec = ClassName("io.mazewall.portal", "PortalCodec")
    private val declaredExceptionMapper = ClassName("io.mazewall.portal", "PortalDeclaredExceptionMapper")
    private val remoteCallException = ClassName("io.mazewall.portal", "PortalRemoteCallException")
    private val capability = ClassName("io.mazewall.portal", "Capability")
    private val readFd = capability.nestedClass("ReadFd")
    private val codecReader = portalCodec.nestedClass("Reader")

    public fun generate(service: Class<*>): List<FileSpec> {
        val schema = ResolvedPortalContract.compile(service)
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
        val stubName = PortalGeneratedNames.stubBinaryName(service).substringAfterLast('.')
        val companionClass = ClassName(service.packageName, stubName).nestedClass("Companion")
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
            type.addFunction(hostMethod(schema.service, method, brokerName, constants.getValue(method.method), companionClass))
        }
        type.addType(companion.build())
        return FileSpec.builder(service.packageName, stubName).addType(type.build()).build()
    }

    private fun hostMethod(
        service: Class<*>,
        method: PortalMethodSchema,
        brokerName: String,
        constantName: String,
        companionClass: ClassName,
    ): FunSpec {
        val kotlinTypes = resolveMethodKotlinTypes(service, method.method)
        val names = NameAllocator()
        val parameterNames =
            method.parameters.associateWith { parameter -> names.newName(parameter.sourceName, parameter) }
        val payloadName = names.newName("payload", HostLocal.Payload)
        val resultName = names.newName("result", HostLocal.Result)
        val readerName = names.newName("reader", HostLocal.Reader)
        val decodedName = names.newName("decoded", HostLocal.Decoded)
        val returnContext = "${service.name}.${method.method.name}.return"
        val spec =
            FunSpec
                .builder(method.method.name)
                .addModifiers(KModifier.PUBLIC, KModifier.OVERRIDE)
                .returns(typeName(method.returnType, kotlinTypes.returnType, kotlinTypes.bindings).copy(nullable = method.returnNullable))
        method.parameters.forEachIndexed { index, parameter ->
            spec.addParameter(
                parameterNames.getValue(parameter),
                typeName(parameter.type, kotlinTypes.parameters.getOrNull(index), kotlinTypes.bindings).copy(nullable = parameter.nullable),
            )
        }

        val payloadParts =
            method.parameters
                .filterNot { it.kind is BoundaryTypes.Kind.ReadFd }
                .map { encode(it.kind, CodeBlock.of("%N", parameterNames.getValue(it)), it.nullable) }
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
                .add("this.%N.invoke(%T.Generated(%T.%N), %N", brokerName, portalMethod, companionClass, constantName, payloadName)
                .apply { granted.forEach { add(", %L", it) } }
                .add(")")
                .build()
        val declaredExceptions = method.method.exceptionTypes
        val remoteName = names.newName("remote", method)
        if (declaredExceptions.isNotEmpty()) spec.beginControlFlow("try")
        if (method.returnKind is BoundaryTypes.Kind.UnitT) {
            spec.addStatement("val %N = %L", resultName, invocation)
            spec.addStatement("%T(%N, %S).requireExhausted()", codecReader, resultName, returnContext)
        } else {
            spec.addStatement("val %N = %L", resultName, invocation)
            spec.addStatement("val %N = %T(%N, %S)", readerName, codecReader, resultName, returnContext)
            spec.addStatement(
                "val %N = %L",
                decodedName,
                decode(
                    method.returnKind,
                    CodeBlock.of("%N", readerName),
                    method.returnNullable,
                    kotlinTypes.returnType,
                    kotlinTypes.bindings,
                ),
            )
            spec.addStatement("%N.requireExhausted()", readerName)
            spec.addStatement("return %N", decodedName)
        }
        if (declaredExceptions.isNotEmpty()) {
            val allowed =
                CodeBlock
                    .builder()
                    .add("arrayOf(")
                    .apply {
                        declaredExceptions.forEachIndexed { index, type ->
                        if (index > 0) add(", ")
                        add("%T::class.java", type.asClassName())
                    }
                    }.add(")")
                    .build()
            spec.nextControlFlow("catch (%N: %T)", remoteName, remoteCallException)
            spec.addStatement("%T.rethrow(%N, %L)", declaredExceptionMapper, remoteName, allowed)
            spec.endControlFlow()
        }
        return spec.build()
    }

    private fun dispatcher(schema: PortalServiceSchema): FileSpec {
        val service = schema.service
        val name = PortalGeneratedNames.dispatcherBinaryName(service).substringAfterLast('.')
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
            val kotlinTypes = resolveMethodKotlinTypes(schema.service, method.method)
            val names = outerNames.copy()
            val argumentNames = method.parameters.associateWith { names.newName(it.sourceName, it) }
            val readerName = names.newName("reader", DispatcherLocal.Reader)
            val returnName = names.newName("ret", DispatcherLocal.Return)
            val methodContext = "${service.name}.${method.method.name}"
            val expectedCapabilities = method.parameters.count { it.kind is BoundaryTypes.Kind.ReadFd }
            var descriptorIndex = 0
            handle.beginControlFlow("%N ->", constants.getValue(method.method))
            handle.addStatement(
                "%T.requireCapabilityCount(%N.size, %L, %S)",
                portalCodec,
                grantedName,
                expectedCapabilities,
                methodContext,
            )
            handle.addStatement("val %N = %T(%N, %S)", readerName, codecReader, payloadName, methodContext)
            method.parameters.forEachIndexed { index, parameter ->
                val argumentName = argumentNames.getValue(parameter)
                if (parameter.kind is BoundaryTypes.Kind.ReadFd) {
                    handle.addComment("Capability slot %L: %N", descriptorIndex, argumentName)
                    handle.addStatement("val %N = %N[%L]", argumentName, grantedName, descriptorIndex++)
                } else {
                    handle.addStatement(
                        "val %N = %N.inContext(%S) { %L }",
                        argumentName,
                        readerName,
                        parameter.sourceName,
                        decode(
                            parameter.kind,
                            CodeBlock.of("%N", readerName),
                            parameter.nullable,
                            kotlinTypes.parameters.getOrNull(index),
                            kotlinTypes.bindings,
                        ),
                    )
                }
            }
            handle.addStatement("%N.requireExhausted()", readerName)
            val arguments = argumentNames.values.map { CodeBlock.of("%N", it) }.joinToCode()
            if (method.returnKind is BoundaryTypes.Kind.UnitT) {
                handle.addStatement("%N.%N(%L)", implName, method.method.name, arguments)
                handle.addStatement("return ByteArray(0)")
            } else {
                handle.addStatement(
                    "val %N: %T = %N.%N(%L)",
                    returnName,
                    typeName(method.returnType, kotlinTypes.returnType, kotlinTypes.bindings).copy(nullable = method.returnNullable),
                    implName,
                    method.method.name,
                    arguments,
                )
                handle.addStatement("return %L", encode(method.returnKind, CodeBlock.of("%N", returnName), method.returnNullable))
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

    private fun typeName(
        type: Type,
        kotlinType: KType? = null,
        bindings: Map<KTypeParameter, KType> = emptyMap(),
    ): TypeName {
        val resolvedKotlinType = resolveKotlinType(kotlinType, bindings)
        return when (type) {
            is ParameterizedType -> parameterizedTypeName(type, resolvedKotlinType, bindings)
            else -> scalarTypeName(type)
        }
    }

    private fun parameterizedTypeName(
        type: ParameterizedType,
        kotlinType: KType?,
        bindings: Map<KTypeParameter, KType>,
    ): TypeName {
        val kotlinArguments = kotlinType?.arguments?.map { it.type }
        val renderedArguments =
            type.actualTypeArguments.mapIndexed { index, argument ->
                typeName(argument, kotlinArguments?.getOrNull(index), bindings)
            }
        return when (type.rawType) {
            java.util.List::class.java -> {
                val collectionType =
                    if (isMutableKotlinCollection(kotlinType, "kotlin.collections.MutableList")) MUTABLE_LIST else LIST
                collectionType.parameterizedBy(renderedArguments)
            }
            java.util.Map::class.java -> {
                val mapType =
                    if (isMutableKotlinCollection(kotlinType, "kotlin.collections.MutableMap")) MUTABLE_MAP else MAP
                mapType.parameterizedBy(renderedArguments)
            }
            else -> (typeName(type.rawType) as ClassName).parameterizedBy(renderedArguments)
        }
    }

    private fun resolveMethodKotlinTypes(
        service: Class<*>,
        method: Method,
    ): MethodKotlinTypes {
        val empty = MethodKotlinTypes(List(method.parameterCount) { null }, null, emptyMap())
        val function =
            method.declaringClass
                .takeIf { it.getAnnotation(Metadata::class.java) != null }
                ?.let { method.kotlinFunction }
                ?: return empty
        val valueParameters = function.parameters.filter { it.kind == KParameter.Kind.VALUE }
        val bindings =
            if (service.getAnnotation(Metadata::class.java) != null) {
                findKotlinTypeBindings(service.kotlin, method.declaringClass.kotlin).orEmpty()
            } else {
                emptyMap()
            }
        return MethodKotlinTypes(valueParameters.map { it.type }, function.returnType, bindings)
    }

    private fun findKotlinTypeBindings(
        current: KClass<*>,
        target: KClass<*>,
        inherited: Map<KTypeParameter, KType> = emptyMap(),
    ): Map<KTypeParameter, KType>? {
        if (current == target) return inherited
        return current.supertypes.firstNotNullOfOrNull { supertype ->
            val raw = supertype.classifier as? KClass<*>
            val args = supertype.arguments.mapNotNull { it.type?.let { type -> resolveKotlinType(type, inherited) } }
            if (raw != null && raw.typeParameters.size == args.size) {
                val next = inherited + raw.typeParameters.zip(args).toMap()
                if (raw == target) next else findKotlinTypeBindings(raw, target, next)
            } else {
                null
            }
        }
    }

    private fun resolveKotlinType(
        kotlinType: KType?,
        bindings: Map<KTypeParameter, KType>,
    ): KType? {
        var current = kotlinType
        val seen = mutableSetOf<KTypeParameter>()
        var next = (current?.classifier as? KTypeParameter)?.takeIf(seen::add)?.let(bindings::get)
        while (next != null) {
            current = next
            next = (current.classifier as? KTypeParameter)?.takeIf(seen::add)?.let(bindings::get)
        }
        return current
    }

    private fun isMutableKotlinCollection(
        kotlinType: KType?,
        expectedRawName: String,
    ): Boolean = kotlinType?.toString()?.substringBefore('<')?.removeSuffix("?") == expectedRawName

    private fun scalarTypeName(type: Type): TypeName =
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
            ByteArray::class.java -> ByteArray::class.asTypeName()
            is Class<*> -> type.asTypeName()
            else -> type.asTypeName()
        }

    private fun encode(
        kind: BoundaryTypes.Kind,
        expression: CodeBlock,
        nullable: Boolean = false,
    ): CodeBlock {
        if (nullable) {
            return CodeBlock.of(
                "if (%L == null) %T.encodeBoolean(false) else %T.concat(listOf(%T.encodeBoolean(true), %L))",
                expression,
                portalCodec,
                portalCodec,
                portalCodec,
                encode(kind, expression),
            )
        }
        return when (kind) {
            BoundaryTypes.Kind.BooleanT,
            BoundaryTypes.Kind.ByteT,
            BoundaryTypes.Kind.ShortT,
            BoundaryTypes.Kind.IntT,
            BoundaryTypes.Kind.LongT,
            BoundaryTypes.Kind.FloatT,
            BoundaryTypes.Kind.DoubleT,
            BoundaryTypes.Kind.CharT,
            BoundaryTypes.Kind.StringT,
            BoundaryTypes.Kind.BytesT,
            -> encodeScalar(kind, expression)
            BoundaryTypes.Kind.UuidT,
            BoundaryTypes.Kind.InstantT,
            BoundaryTypes.Kind.BigDecimalT,
            -> CodeBlock.of("%T.encodeString(%L.toString())", portalCodec, expression)
            is BoundaryTypes.Kind.EnumT -> CodeBlock.of("%T.encodeString(%L.name)", portalCodec, expression)
            BoundaryTypes.Kind.ReadFd -> error("ReadFd is not encoded in the payload")
            is BoundaryTypes.Kind.InputSnapshot ->
                CodeBlock.of(
                    "%T.capture(%L, %L).use { %T.encodeBytes(it.bytes()) }",
                    io.mazewall.portal.PortalInputSnapshot::class,
                    expression,
                    kind.maximumBytes,
                    portalCodec,
                )
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
            is BoundaryTypes.Kind.ListT ->
                CodeBlock.of(
                    "%T.concat(listOf(%T.encodeInt(%L.size)) + %L.map { item -> %L })",
                    portalCodec,
                    portalCodec,
                    expression,
                    expression,
                    encode(kind.element, CodeBlock.of("item")),
                )
            is BoundaryTypes.Kind.MapT ->
                CodeBlock.of(
                    "%T.concat(listOf(%T.encodeInt(%L.size)) + %L.entries.flatMap { entry -> listOf(%L, %L) })",
                    portalCodec,
                    portalCodec,
                    expression,
                    expression,
                    encode(kind.key, CodeBlock.of("entry.key")),
                    encode(kind.value, CodeBlock.of("entry.value")),
                )
        }
    }

    private fun encodeScalar(
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
            is BoundaryTypes.Kind.EnumT,
            BoundaryTypes.Kind.ReadFd,
            is BoundaryTypes.Kind.InputSnapshot,
            BoundaryTypes.Kind.UuidT,
            BoundaryTypes.Kind.InstantT,
            BoundaryTypes.Kind.BigDecimalT,
            BoundaryTypes.Kind.UnitT,
            is BoundaryTypes.Kind.RecordT,
            is BoundaryTypes.Kind.ListT,
            is BoundaryTypes.Kind.MapT,
            -> error("non-scalar portal boundary kind: $kind")
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
        nullable: Boolean = false,
        kotlinType: KType? = null,
        bindings: Map<KTypeParameter, KType> = emptyMap(),
    ): CodeBlock {
        val resolvedKotlinType = resolveKotlinType(kotlinType, bindings)
        if (nullable) {
            return CodeBlock.of(
                "if (!%L.boolean()) null else %L",
                reader,
                decode(kind, reader, kotlinType = resolvedKotlinType, bindings = bindings),
            )
        }
        return when (kind) {
            BoundaryTypes.Kind.BooleanT,
            BoundaryTypes.Kind.ByteT,
            BoundaryTypes.Kind.ShortT,
            BoundaryTypes.Kind.IntT,
            BoundaryTypes.Kind.LongT,
            BoundaryTypes.Kind.FloatT,
            BoundaryTypes.Kind.DoubleT,
            BoundaryTypes.Kind.CharT,
            BoundaryTypes.Kind.StringT,
            BoundaryTypes.Kind.BytesT,
            -> decodeScalar(kind, reader)
            BoundaryTypes.Kind.UuidT -> CodeBlock.of("%L.uuid()", reader)
            BoundaryTypes.Kind.InstantT -> CodeBlock.of("%L.instant()", reader)
            BoundaryTypes.Kind.BigDecimalT -> CodeBlock.of("%L.bigDecimal()", reader)
            is BoundaryTypes.Kind.EnumT -> CodeBlock.of("%L.enumValue(%T::class.java)", reader, kind.typeName)
            BoundaryTypes.Kind.ReadFd -> error("ReadFd is taken from granted FDs")
            is BoundaryTypes.Kind.InputSnapshot -> CodeBlock.of("%L.bytes().inputStream()", reader)
            BoundaryTypes.Kind.UnitT -> CodeBlock.of("Unit")
            is BoundaryTypes.Kind.RecordT -> {
                CodeBlock
                    .builder()
                    .add("%T(", kind.typeName)
                    .add(
                        "%L",
                        kind.components
                            .map { component ->
                                CodeBlock.of("%L.inContext(%S) { %L }", reader, component.name, decode(component.kind, reader))
                            }.joinToCode(),
                    ).add(")")
                    .build()
            }
            is BoundaryTypes.Kind.ListT -> {
                val elementKotlinType = resolvedKotlinType?.arguments?.singleOrNull()?.type
                val suffix = if (isMutableKotlinCollection(resolvedKotlinType, "kotlin.collections.MutableList")) ".toMutableList()" else ""
                CodeBlock.of(
                    "buildList { repeat(%L.collectionCount()) { add(%L) } }$suffix",
                    reader,
                    decode(kind.element, reader, kotlinType = elementKotlinType, bindings = bindings),
                )
            }
            is BoundaryTypes.Kind.MapT -> {
                val keyKotlinType = resolvedKotlinType?.arguments?.getOrNull(0)?.type
                val valueKotlinType = resolvedKotlinType?.arguments?.getOrNull(1)?.type
                val method = if (isMutableKotlinCollection(resolvedKotlinType, "kotlin.collections.MutableMap")) "mutableMap" else "map"
                CodeBlock.of(
                    "%L.$method({ %L }, { %L })",
                    reader,
                    decode(kind.key, reader, kotlinType = keyKotlinType, bindings = bindings),
                    decode(kind.value, reader, kotlinType = valueKotlinType, bindings = bindings),
                )
            }
        }
    }

    private fun decodeScalar(
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
            is BoundaryTypes.Kind.EnumT,
            BoundaryTypes.Kind.ReadFd,
            is BoundaryTypes.Kind.InputSnapshot,
            BoundaryTypes.Kind.UuidT,
            BoundaryTypes.Kind.InstantT,
            BoundaryTypes.Kind.BigDecimalT,
            BoundaryTypes.Kind.UnitT,
            is BoundaryTypes.Kind.RecordT,
            is BoundaryTypes.Kind.ListT,
            is BoundaryTypes.Kind.MapT,
            -> error("non-scalar portal boundary kind: $kind")
        }

    private data class MethodKotlinTypes(
        val parameters: List<KType?>,
        val returnType: KType?,
        val bindings: Map<KTypeParameter, KType>,
    )

    private enum class HostLocal { Payload, Result, Reader, Decoded }

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
