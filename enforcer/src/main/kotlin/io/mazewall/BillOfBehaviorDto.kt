package io.mazewall

import kotlinx.serialization.Serializable

@Serializable
public class BillOfBehaviorDto(
    val opens: Set<String> = emptySet(),
    val fsWritePaths: Set<String> = emptySet(),
    val syscalls: Set<String> = emptySet(),
    val execs: Set<String> = emptySet(),
    val stackProfile: List<StackProfileEntryDto> = emptyList(),
)

@Serializable
public class StackProfileEntryDto(
    val syscall: String,
    val paths: List<String> = emptyList(),
    val args: List<Long> = emptyList(),
    val stackTrace: List<StackFrameDto> = emptyList(),
)

@Serializable
public data class StackFrameDto(
    val classLoader: String? = null,
    val module: String? = null,
    val moduleVersion: String? = null,
    val className: String,
    val methodName: String,
    val fileName: String? = null,
    val lineNumber: Int = -1,
)

