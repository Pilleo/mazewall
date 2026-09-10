package io.mazewall.portal.codegen.testapi

import io.mazewall.portal.Capability

interface SampleGreeter {
    fun greet(name: String): String

    fun add(
        a: Int,
        b: Int,
    ): Int
}

interface SampleGeom {
    fun magnitude(p: SamplePoint): Int
}

interface SampleFd {
    fun checksum(fd: Capability.ReadFd): Int
}

interface BadStream {
    fun read(s: java.io.InputStream): Int
}

interface Overloads {
    fun echo(s: String): String

    fun echo(n: Int): String
}

interface InheritedBase {
    fun inherited(value: String): String

    fun overridden(value: Int): Int
}

interface InheritedService : InheritedBase {
    override fun overridden(value: Int): Int

    fun payload(
        payload: String,
        reader: String,
        result: String,
        impl: String,
        method: String,
        granted: String,
        broker: String,
    ): String

    fun ping(value: Int): Int

    fun PING(value: String): String
}

interface ValidPojoService {
    fun echo(value: ValidPojo): ValidPojo
}

interface UnreadablePojoService {
    fun echo(value: UnreadablePojo): UnreadablePojo
}

interface WrongReaderPojoService {
    fun echo(value: WrongReaderPojo): WrongReaderPojo
}

data class KotlinDataDto(
    val name: String,
    val count: Int,
)

@JvmRecord
data class KotlinJvmRecordDto(
    val name: String,
    val count: Int,
)

interface KotlinDataDtoService {
    fun echoKotlin(value: KotlinDataDto): KotlinDataDto
}

interface KotlinJvmRecordDtoService {
    fun echoKotlinJvmRecord(value: KotlinJvmRecordDto): KotlinJvmRecordDto
}
