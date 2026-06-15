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
    val stackTrace: List<String> = emptyList(),
)

