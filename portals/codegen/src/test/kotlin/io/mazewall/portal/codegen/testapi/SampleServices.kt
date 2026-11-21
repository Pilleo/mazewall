package io.mazewall.portal.codegen.testapi

import io.mazewall.portal.Capability
import io.mazewall.portal.PortalInputSnapshotAdapter

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

internal interface SampleFd {
    fun checksum(fd: Capability.ReadFd): Int
}

interface BadStream {
    fun read(s: java.io.InputStream): Int
}

interface SnapshotStream {
    fun read(
        @PortalInputSnapshotAdapter(maximumBytes = 64) source: java.io.InputStream,
    ): Int
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

class FirstOuter {
    interface NestedService {
        fun echo(value: String): String
    }
}

class SecondOuter {
    interface NestedService {
        fun echo(value: String): String
    }
}

interface CompanionShadowService {
    fun echo(Companion: String): String
}

interface NullableService {
    fun echo(value: String?): String?
}

internal interface ReturnedFdService {
    fun acquire(): Capability.ReadFd
}

internal data class NestedFd(
    val fd: Capability.ReadFd,
)

internal interface NestedFdService {
    fun consume(value: NestedFd): Int
}

class ComputedDto(
    value: Int,
) {
    val value: Int get() = 1
}

class DelegatedDto(
    value: Int,
) {
    val value: Int by lazy { value }
}

interface ComputedDtoService {
    fun consume(value: ComputedDto): Int
}

interface DelegatedDtoService {
    fun consume(value: DelegatedDto): Int
}

interface GenericKotlinCollectionService<T> {
    fun echoReadOnly(values: List<T>): List<T>

    fun echoMutable(values: MutableList<T>): MutableList<T>
}

interface BinaryAndCollectionService : GenericKotlinCollectionService<ByteArray> {
    fun echoBytes(value: ByteArray): ByteArray

    fun echoNested(values: Map<String, List<ByteArray>>): MutableMap<String, MutableList<ByteArray>>
}

data class StrictDto(
    val label: String,
    val count: Int,
) {
    init {
        require(label != "invalid-dto") { "rejected by dto" }
    }
}

interface StrictContractService {
    fun unitOp()

    fun echoInt(value: Int): Int

    fun echoBool(value: Boolean): Boolean

    fun echoString(value: String): String

    fun echoMap(values: Map<String, Int>): Map<String, Int>

    fun buildDto(label: String): StrictDto

    fun acceptDto(value: StrictDto): Int

    fun readGrant(
        fd: Capability.ReadFd,
        label: String,
    ): Int
}

abstract class AbstractDto(
    val count: Int,
)

interface AbstractDtoService {
    fun echo(dto: AbstractDto): Int
}

data class NullableMemberDto(
    val name: String?,
)

interface NullableMemberDtoService {
    fun echo(dto: NullableMemberDto): Int
}

class EnclosingScope {
    inner class NonStaticMemberDto(
        val value: Int,
    )
}

interface NonStaticMemberDtoService {
    fun echo(dto: EnclosingScope.NonStaticMemberDto): Int
}

interface PrivateConstructorPojoService {
    fun echo(dto: PrivateConstructorPojo): Int
}

interface NamesRecordService {
    fun echo(names: NamesRecord): NamesRecord
}

interface NamesPojoService {
    fun echo(pojo: NamesPojo): NamesPojo
}

data class KotlinGenericDto(
    val values: List<String>,
)

interface KotlinGenericDtoService {
    fun echo(dto: KotlinGenericDto): KotlinGenericDto
}

data class NullableListMemberDto(
    val values: List<String?>,
)

interface NullableListMemberDtoService {
    fun echo(dto: NullableListMemberDto): Int
}
