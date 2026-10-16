package io.mazewall.enforcer.api

import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.state.*
import kotlinx.serialization.Serializable

@Serializable
public class BillOfBehaviorDto(
    public val opens: Set<String> = emptySet(),
    public val fsWritePaths: Set<String> = emptySet(),
    public val syscalls: Set<String> = emptySet(),
    public val execs: Set<String> = emptySet(),
    public val connects: Set<String> = emptySet(),
    public val ioUringOps: Set<String> = emptySet(),
    public val stackProfile: List<StackProfileEntryDto> = emptyList(),
)

@Serializable
public class StackProfileEntryDto(
    public val syscall: String,
    public val paths: List<String> = emptyList(),
    public val args: List<Long> = emptyList(),
    public val stackTrace: List<StackFrameDto> = emptyList(),
    /** Additive typed open evidence; absent fields preserve legacy JSON compatibility. */
    public val openFlagsEvidenceValue: Long? = null,
    public val openFlagsUnknownSymbols: Set<String> = emptySet(),
)

@Serializable
public data class StackFrameDto(
    public val classLoader: String? = null,
    public val module: String? = null,
    public val moduleVersion: String? = null,
    public val className: String,
    public val methodName: String,
    public val fileName: String? = null,
    public val lineNumber: Int = -1,
)
