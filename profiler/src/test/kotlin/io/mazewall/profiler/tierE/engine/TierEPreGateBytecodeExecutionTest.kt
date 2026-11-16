package io.mazewall.profiler.tierE.engine

import io.mazewall.profiler.internal.attribution.TierEOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Executes the emitted diagnostic prefix, including real relative jumps and helper outcomes. */
class TierEPreGateBytecodeExecutionTest {
    @Test
    fun capacityAndAttemptedCountsReconcile() {
        val program = TierEBpfProgramBuilder.buildBoundaryDiagnosticSysEnterProgram(TierEOptions(allTargetCapture = true), 12345)
        val vm = DiagnosticPrefix(program, 12345, boundaryMode = true)
        repeat(32769) { vm.run(257, 12345) }
        assertEquals(32768, vm.boundaryRows.size)
        assertEquals(listOf(32769L, 32768L, 32768L, 32768L, 1L, 0L, 0L, 0L), vm.boundaryControl.take(8))
        assertEquals(3, vm.boundaryRows.first()[0].toInt())
        assertEquals(3, vm.boundaryRows.last()[0].toInt())
    }

    @Test
    fun immutableTargetAndEveryEarlyMainBranchRetainOnlyObservedWords() {
        val program = TierEBpfProgramBuilder.buildBoundaryDiagnosticSysEnterProgram(TierEOptions(allTargetCapture = true), 12345)
        val wrong = DiagnosticPrefix(program, 12345, boundaryMode = true)
        wrong.run(257, 12346)
        assertEquals(0L, wrong.boundaryControl[0])
        assertEquals(0, wrong.boundaryRows.size)
        val session = longArrayOf((12345L shl 32) or 0x4d5a, (7L shl 32) or 1, 1)

        fun stage(
            expected: BoundaryStage,
            state: LongArray = session,
            missingMap: Pair<Int, Int>? = null,
            noTask: Boolean = false,
        ) {
            val vm = DiagnosticPrefix(program, 12345, boundaryMode = true)
            vm.boundarySession = state
            vm.boundaryMissingMap = missingMap
            vm.boundaryNoTask = noTask
            vm.run(257, 12345)
            assertEquals(expected.encoding, vm.boundaryRows.single()[12].toInt())
            assertEquals(0L, vm.boundaryControl[6])
            assertEquals(0, vm.argumentReads, "early rejection must not add argument reads")
        }
        stage(BoundaryStage.MAIN_WRONG_TARGET, session.copyOf().apply { this[0] += 1L shl 32 })
        stage(BoundaryStage.MAIN_NO_INFLIGHT, missingMap = 6 to 0)
        stage(BoundaryStage.MAIN_DETACHED, session.copyOf().apply { this[1] = 7L shl 32 })
        stage(BoundaryStage.MAIN_NO_TASK, noTask = true)
        stage(BoundaryStage.MAIN_NO_STORAGE)
    }

    @Test
    fun markerEarlyRejectionsAndRawPostStoreWidthsAreActual() {
        val program = TierEBpfProgramBuilder.buildBoundaryDiagnosticMarkerProgram(TierEOptions(allTargetCapture = true), 12345)
        val session = longArrayOf((12345L shl 32) or 0x4d5a, (7L shl 32) or 1, 1)
        val incoming = 0x4d5a0000002aL

        fun stage(
            expected: BoundaryStage,
            config: LongArray? = session,
            id: Long = incoming,
            missing: Pair<Int, Int>? = null,
            noTask: Boolean = false,
            secondLookup: Boolean = false,
        ) {
            val vm = DiagnosticPrefix(program, 12345, boundaryMode = true)
            vm.boundarySession = config
            vm.boundaryMissingMap = missing
            vm.boundaryNoTask = noTask
            vm.boundarySecondLookupMissing = secondLookup
            vm.run(257, 12345, rawSyscall = id)
            assertEquals(expected.encoding, vm.boundaryRows.single()[12].toInt())
            assertEquals(0L, vm.boundaryControl[7])
        }
        stage(BoundaryStage.MARKER_CONFIG_MISSING, null)
        stage(BoundaryStage.MARKER_WRONG_TARGET, session.copyOf().apply { this[0] += 1L shl 32 })
        stage(BoundaryStage.MARKER_SESSION_MISSING, secondLookup = true)
        stage(BoundaryStage.MARKER_WRONG_SESSION, id = incoming + (1L shl 32))
        stage(BoundaryStage.MARKER_COUNTER_MISSING, missing = 1 to 1)
        stage(BoundaryStage.MARKER_PID_SLOT_MISSING, missing = 1 to 2)
        stage(BoundaryStage.MARKER_NO_TASK, noTask = true)
        val vm = DiagnosticPrefix(program, 12345, boundaryMode = true)
        vm.boundarySession = session
        vm.boundaryStorage = longArrayOf(0, 0, 0)
        vm.boundaryIncomingFlags = 0x123400000011L
        vm.run(257, 12345, rawSyscall = incoming)
        assertEquals(0x123400000011L, vm.boundaryRows.single()[9], "incoming flags retain full observed word")
        assertEquals(17L, vm.boundaryRows.single()[10], "post-store flags are the actual zero-extended u32")
    }

    @Test
    fun lostReceiptAndProductionHelperRemainIncompleteWithoutChangingProductionBranch() {
        val program = TierEBpfProgramBuilder.buildBoundaryDiagnosticSysEnterProgram(TierEOptions(allTargetCapture = true), 12345)
        val vm = DiagnosticPrefix(program, 12345, boundaryMode = true)
        vm.boundarySession = longArrayOf((12345L shl 32) or 0x4d5a, (7L shl 32) or 1, 1)
        vm.boundaryStorage = longArrayOf(123, 9, 7L shl 32)
        vm.boundaryMissingMap = 12 to 0
        vm.run(257, 12345)
        assertEquals(1L, vm.metric[0], "failed diagnostic reservation must not suppress production")
        assertEquals(listOf(1L, 1L, 0L, 0L, 0L, 1L, 0L, 0L), vm.boundaryControl.take(8))
        assertEquals(0, vm.boundaryRows.single()[0].toInt(), "uncommitted reservation is retained")
        val partial = DiagnosticPrefix(program, 12345, boundaryMode = true)
        partial.boundarySession = vm.boundarySession
        partial.boundaryMissingMap = 2 to 13
        partial.run(257, 12345)
        assertEquals(1, partial.boundaryRows.single()[0].toInt(), "unknown production helper completion stays partial")
        assertEquals(1L, partial.boundaryControl[5])
        assertEquals(0L, partial.boundaryControl[6])
    }

    @Test
    fun counterExhaustionCannotWrapOrOverwrite() {
        val program = TierEBpfProgramBuilder.buildBoundaryDiagnosticSysEnterProgram(TierEOptions(allTargetCapture = true), 12345)
        val vm = DiagnosticPrefix(program, 12345, boundaryMode = true)
        vm.boundaryControl[0] = Long.MAX_VALUE
        vm.run(257, 12345)
        assertEquals(Long.MAX_VALUE, vm.boundaryControl[0], "attempt counter must not wrap")
        assertEquals(0, vm.boundaryRows.size, "exhaustion cannot reuse slot zero")
        assertEquals(0L, vm.boundaryControl[6], "failed reservation must complete diagnostics")
        org.junit.jupiter.api.Assertions
            .assertTrue(vm.boundaryControl[5] > 0)
    }

    @Test
    fun entryPrecedesMutableGatesAndActiveCounter() {
        val program = TierEBpfProgramBuilder.buildBoundaryDiagnosticSysEnterProgram(TierEOptions(allTargetCapture = true), 12345)
        val snapshot = executeBoundaryDiagnostic(program, 12345, -1L, null, null, 32768)
        assertEquals(1, snapshot.records.size, "missing owned-target entry before session lookup")
        assertEquals(BoundaryStage.MAIN_NO_SESSION, snapshot.records.single().stage)
        assertEquals(0u, snapshot.records.single().validFields)
        assertEquals(ULong.MAX_VALUE, snapshot.records.single().rawId)
    }

    @Test
    fun mainRetainsActualGateValues() {
        val program = TierEBpfProgramBuilder.buildBoundaryDiagnosticSysEnterProgram(TierEOptions(allTargetCapture = true), 12345)
        val session = longArrayOf((12345L shl 32) or 0x4d5a, (7L shl 32) or 1, 1)
        val storage = longArrayOf(123, 9, (7L shl 32) or 8)
        val disabled = executeBoundaryDiagnostic(program, 12345, 257, session.copyOf().apply { this[2] = 0 }, storage, 32768).records.single()
        assertEquals(BoundaryStage.MAIN_CAPTURE_DISABLED, disabled.stage)
        assertEquals(19u, disabled.validFields)
        assertEquals(listOf(12345uL, 0uL), disabled.words.take(2))
        val inactive = executeBoundaryDiagnostic(program, 12345, 257, session, storage.copyOf().apply { this[0] = 0 }, 32768).records.single()
        assertEquals(BoundaryStage.MAIN_INACTIVE, inactive.stage, inactive.toString())
        assertEquals(95u, inactive.validFields)
        assertEquals(0uL, inactive.invocation)
        val stale = executeBoundaryDiagnostic(program, 12345, 257, session, storage.copyOf().apply { this[2] = (6L shl 32) or 8 }, 32768).records.single()
        assertEquals(BoundaryStage.MAIN_STALE, stale.stage)
        assertEquals(7uL, stale.words[3])
        assertEquals(6u, stale.generation)
        assertEquals(8u, stale.aux)
        assertEquals(991u, stale.validFields)
        assertEquals(9uL, stale.sequence)
        val missing = executeBoundaryDiagnostic(program, 12345, 257, session, null, 32768).records.single()
        assertEquals(BoundaryStage.MAIN_NO_STORAGE, missing.stage)
        val active = executeBoundaryDiagnostic(program, 12345, 257, session, storage, 32768)
        assertEquals(BoundaryStage.MAIN_ACTIVE_COUNTER_REACHED, active.records.single().stage)
        assertEquals(0L, active.control.mainInFlight)
    }

    @Test
    fun markerFailureCannotBecomeSetAndClearHasAdvancedSequence() {
        val program = TierEBpfProgramBuilder.buildBoundaryDiagnosticMarkerProgram(TierEOptions(allTargetCapture = true), 12345)
        val session = longArrayOf((12345L shl 32) or 0x4d5a, (7L shl 32) or 1, 1)
        val incoming = 0x4d5a0000002aL
        val failed = executeBoundaryDiagnostic(program, 12345, incoming, session, null, 32768)
        assertEquals(1, failed.records.size, "missing marker failure receipt")
        assertEquals(BoundaryStage.MARKER_STORAGE_FAILED, failed.records.single().stage)
        assertEquals(15u, failed.records.single().validFields)
        assertEquals(0uL, failed.records.single().words[2])
        val storage = longArrayOf(123, 9, (7L shl 32) or 8)
        val set = executeBoundaryDiagnostic(program, 12345, incoming, session, storage, 32768)
        assertEquals(BoundaryStage.MARKER_SET, set.records.single().stage)
        assertEquals(incoming.toULong(), storage[0].toULong())
        assertEquals(10uL, set.records.single().sequence)
        val clear = executeBoundaryDiagnostic(program, 12345, 0, session, storage, 32768)
        assertEquals(BoundaryStage.MARKER_CLEAR, clear.records.single().stage)
        assertEquals(0uL, clear.records.single().invocation)
        assertEquals(11uL, clear.records.single().sequence)
        assertEquals(990u, clear.records.single().validFields, "clear did not perform a session-tag lookup")
        assertEquals(0L, clear.control.markerInFlight)
    }

    private fun executeBoundaryDiagnostic(
        program: List<TierEbpfEngine.Insn>,
        configuredTarget: Int,
        syscallContext: Long,
        sessionState: LongArray?,
        storageState: LongArray?,
        capacity: Int,
    ): BoundaryDiagnosticSnapshot {
        val vm = DiagnosticPrefix(program, configuredTarget, boundaryMode = true)
        vm.boundarySession = sessionState
        vm.boundaryStorage = storageState
        vm.run(syscallContext.toInt(), configuredTarget, rawSyscall = syscallContext)
        val bytes = java.nio.ByteBuffer
            .allocate(256 + vm.boundaryRows.size * 112)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bytes
            .put(byteArrayOf(77, 90, 66, 68))
            .putShort(1)
            .putShort(128)
            .putInt(112)
            .putInt(capacity)
        bytes
            .putLong(0x4d5a)
            .putLong(configuredTarget.toLong())
            .putInt(1)
            .putInt(1)
        bytes.position(128)
        vm.boundaryControl.forEach { bytes.putLong(it) }
        bytes.position(256)
        vm.boundaryRows.forEach { row -> row.forEach { bytes.putLong(it) } }
        return decodeBoundaryDiagnostics(bytes.array())
    }

    private val options = TierEOptions(benchmarkPhases = true, benchmarkRawSysEnterAudit = true, benchmarkExactAdmissionAudit = true)

    @Test
    fun `executed raw and main programs count marker ID even when FD argument read fails`() {
        val tokenOptions = options.copy(benchmarkInvocationIdWitness = true)
        val main = DiagnosticPrefix(TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(tokenOptions, 12345), 12345)
        main.run(syscall = 257, pid = 12345, firstReadFails = true)
        assertEquals(1L, main.witness[1], "main entered before argument read")
        assertEquals(0L, main.pre[2], "failed FD read remains absent")

        val raw = DiagnosticPrefix(TierEBpfProgramBuilder.buildRawTracepointSysEnterAuditProgram(true, 12345, true), 12345, rawProgram = true)
        raw.run(syscall = 257, pid = 12345, firstReadFails = true)
        assertEquals(1L, raw.witness[0], "raw entered before argument read")
        assertEquals(0L, raw.pre[1])
    }

    @Test
    fun `executed witness excludes wrong task and missing token cannot fabricate an ID`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterAuditProgram(true, 12345, true)
        val machine = DiagnosticPrefix(program, 12345, rawProgram = true)
        machine.run(syscall = 257, pid = 12346)
        machine.run(syscall = 257, pid = 12345, markerTokenZero = true)
        assertEquals(0L, machine.witness[0])
        assertEquals(1L, machine.failure(11), "armed target missing marker token must remain explicit")
    }

    @Test
    fun `executed witness records map insertion failure and does not count prearm JVM noise as lost tokens`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterAuditProgram(true, 12345, true)
        val missingMap = DiagnosticPrefix(program, 12345, rawProgram = true)
        missingMap.run(syscall = 257, pid = 12345, witnessInsertFails = true)
        assertEquals(0L, missingMap.witness[0])
        assertEquals(1L, missingMap.failure(13), "failed witness-map insertion is not a missing callback")

        val beforeArm = DiagnosticPrefix(program, 12345, rawProgram = true)
        beforeArm.run(syscall = 257, pid = 12345, armed = false, markerTokenMissing = true)
        assertEquals(0L, beforeArm.failure(9), "prearm JVM activity must not invalidate the measured interval")
    }

    @Test
    fun `failed first argument read cannot let later successful read invent post session admission`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(options, benchmarkTargetTgid = 12345)
        val machine = DiagnosticPrefix(program, target = 12345)
        machine.run(syscall = 257, pid = 12345, firstReadFails = true)
        assertEquals(0L, machine.pre[2], "failed first read cannot produce a validated pre-session descriptor")
        assertEquals(0L, machine.pre[4], "later v3 read must not invent the v4 post-session checkpoint")
        assertEquals(1L, machine.failure(4), "main read failure must remain explicit")
    }

    @Test
    fun `later helper result cannot redirect validated descriptor to a different ID`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(options, benchmarkTargetTgid = 12345)
        val machine = DiagnosticPrefix(program, target = 12345)
        machine.run(syscall = 257, pid = 12345, secondReadFd = 222)
        assertEquals(1L, machine.pre[2], "the first decoded ID passed the independent pre-session checkpoint")
        assertEquals(1L, machine.pre[4], "the post-session stage must use that same validated ID")
        assertEquals(0L, machine.other[4], "the second argument read must not redirect post-session evidence")
    }

    @Test
    fun `unarmed and wrong target syscalls cannot increment pre or post descriptor stages`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(options, benchmarkTargetTgid = 12345)
        val machine = DiagnosticPrefix(program, target = 12345)
        machine.run(syscall = 257, pid = 12345, armed = false)
        assertEquals(0L, machine.pre[2], "unarmed event")
        assertEquals(0L, machine.pre[4], "unarmed post stage")
        machine.run(syscall = 257, pid = 12346)
        assertEquals(0L, machine.pre[2], "wrong target event")
        assertEquals(0L, machine.pre[4], "wrong target post stage")
    }

    @Test
    fun `prior eligible syscall cannot leak its descriptor into a later non openat`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(options, benchmarkTargetTgid = 12345)
        val machine = DiagnosticPrefix(program, target = 12345)
        machine.run(syscall = 257, pid = 12345)
        machine.run(syscall = 3, pid = 12345)
        assertEquals(1L, machine.pre[2])
        assertEquals(1L, machine.pre[4])
    }

    @Test
    fun `session mismatch retains pre stage without manufacturing post stage`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(options, benchmarkTargetTgid = 12345)
        val machine = DiagnosticPrefix(program, target = 12346)
        machine.run(syscall = 257, pid = 12345)
        assertEquals(1L, machine.pre[2])
        assertEquals(0L, machine.pre[4])
    }

    @Test
    fun `missing pre gate map lookup cannot admit post stage and records explicit failure`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(options, benchmarkTargetTgid = 12345)
        val machine = DiagnosticPrefix(program, target = 12345)
        machine.run(syscall = 257, pid = 12345, missingPreGateEntry = true)
        assertEquals(0L, machine.pre[2])
        assertEquals(0L, machine.pre[4])
        assertEquals(1L, machine.failure(6))
    }

    @Test
    fun `raw program records only validated declared entries across failed helpers and consecutive syscalls`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterAuditProgram(earlyTargetCount = true, benchmarkTargetTgid = 12345)
        val machine = DiagnosticPrefix(program, target = 12345, rawProgram = true)
        machine.run(syscall = 257, pid = 12345, firstReadFails = true)
        assertEquals(0L, machine.pre[1])
        assertEquals(0L, machine.pre[3])
        assertEquals(1L, machine.failure(3))
        machine.run(syscall = 257, pid = 12345, secondReadFd = 222)
        assertEquals(1L, machine.pre[1])
        assertEquals(1L, machine.pre[3])
        assertEquals(0L, machine.other[3])
        machine.run(syscall = 3, pid = 12345)
        machine.run(syscall = 257, pid = 12346)
        assertEquals(1L, machine.pre[1])
        assertEquals(1L, machine.pre[3])
    }

    @Test
    fun `raw probe keeps a pre stage on session rejection and excludes failed descriptor lookup`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterAuditProgram(earlyTargetCount = true, benchmarkTargetTgid = 12345)
        val rejected = DiagnosticPrefix(program, target = 12346, rawProgram = true)
        rejected.run(syscall = 257, pid = 12345)
        assertEquals(1L, rejected.pre[1])
        assertEquals(0L, rejected.pre[3])
        val noEntry = DiagnosticPrefix(program, target = 12345, rawProgram = true)
        noEntry.run(syscall = 257, pid = 12345, missingPreGateEntry = true)
        assertEquals(0L, noEntry.pre[1])
        assertEquals(0L, noEntry.pre[3])
        assertEquals(1L, noEntry.failure(5))
    }

    private class DiagnosticPrefix(
        private val program: List<TierEbpfEngine.Insn>,
        private val target: Int,
        private val rawProgram: Boolean = false,
        private val boundaryMode: Boolean = false,
    ) {
        private val boundaryMarker = boundaryMode && program[2].dst == 6
        var boundarySession: LongArray? = null
        var boundaryStorage: LongArray? = null
        var boundaryMissingMap: Pair<Int, Int>? = null
        var boundaryNoTask = false
        var boundarySecondLookupMissing = false
        var boundaryIncomingFlags = 17L
        val metric = LongArray(1)
        var argumentReads = 0
        val boundaryRows = mutableListOf<LongArray>()
        val boundaryControl = LongArray(16)
        val pre = LongArray(5).apply { this[0] = 1 }
        val other = LongArray(5).apply { this[0] = 1 }
        private val earlyCounters = Array(15) { LongArray(1) }
        private val stack = mutableMapOf<Int, Long>()
        private val pointerSpills = mutableMapOf<Int, Value>()
        val witness = LongArray(2)
        private var witnessInserted = false

        fun failure(key: Int): Long = earlyCounters[key][0]

        private sealed interface Value {
            data class Number(
                val value: Long,
            ) : Value

            data class Address(
                val offset: Int,
            ) : Value

            data class Map(
                val index: Int,
            ) : Value

            data class Entry(
                val map: Int,
                val index: Int,
                val value: LongArray,
            ) : Value

            data object Context : Value

            data object Unreadable : Value

            data class PtRegs(
                val offset: Int,
            ) : Value
        }

        fun run(
            syscall: Int,
            pid: Int,
            armed: Boolean = true,
            firstReadFails: Boolean = false,
            secondReadFd: Int = 111,
            missingPreGateEntry: Boolean = false,
            markerTokenMissing: Boolean = false,
            markerTokenZero: Boolean = false,
            witnessInsertFails: Boolean = false,
            rawSyscall: Long = syscall.toLong(),
        ) {
            earlyCounters[2][0] = if (armed) 1 else 0
            val registers = MutableList<Value>(11) { Value.Number(0) }
            registers[1] = Value.Context
            registers[10] = Value.Address(0)
            var reads = 0
            var sessionLookups = 0
            var pc = 0
            var steps = 0

            fun number(register: Int): Long =
                when (val value = registers[register]) {
                is Value.Number -> value.value
                is Value.Entry -> 1L // non-null map-value pointer compared against zero
                else -> error("register $register is not comparable")
            }
            while (pc in program.indices && ++steps < if (boundaryMode) 10000 else 1600) {
                val insn = program[pc]
                if (!boundaryMode && insn.code == 0x7b && insn.off == (-112).toShort()) return // next step enters in-flight attribution
                if (!boundaryMode && !rawProgram && insn.code == 0x18 && insn.imm == 6) return // main in-flight path is outside this diagnostic prefix
                if (rawProgram && insn.code == 0x85 && insn.imm == 8) return // CPU audit follows the raw post-session stage
                var next = pc + 1
                when (insn.code) {
                    0x00 -> Unit
                    0x18 -> {
                        registers[insn.dst] = if (insn.src == 1) {
                            Value.Map(insn.imm)
                        } else {
                            Value.Number((insn.imm.toLong() and 0xffff_ffffL) or (program[pc + 1].imm.toLong() shl 32))
                        }
                        next++
                    }
                    0xb7 -> registers[insn.dst] = Value.Number(insn.imm.toLong())
                    0xbf -> registers[insn.dst] = registers[insn.src]
                    0xbc -> registers[insn.dst] = Value.Number(number(insn.src) and 0xffff_ffffL)
                    0x07 -> registers[insn.dst] = when (val value = registers[insn.dst]) {
                        is Value.Address -> value.copy(offset = value.offset + insn.imm)
                        is Value.PtRegs -> value.copy(offset = value.offset + insn.imm)
                        is Value.Number -> value.copy(value = value.value + insn.imm)
                        else -> error("unsupported add at $pc")
                    }
                    0x77 -> registers[insn.dst] = Value.Number(number(insn.dst) ushr insn.imm)
                    0x47 -> registers[insn.dst] = Value.Number(number(insn.dst) or insn.imm.toLong())
                    0x63, 0x7b -> when (val destination = registers[insn.dst]) {
                        is Value.Address -> {
                            val offset = destination.offset + insn.off
                            val source = registers[insn.src]
                            check(source != Value.Unreadable) { "verifier-unreadable register ${insn.src} stored at $pc" }
                            if (source is Value.Number) {
                                pointerSpills.remove(offset)
                                stack[offset] = source.value
                            } else {
                                check(insn.code == 0x7b)
                                pointerSpills[offset] = source
                            }
                        }
                        is Value.Entry -> {
                            val index = insn.off.toInt() / 8
                            val value = number(insn.src)
                            destination.value[index] = if (insn.code == 0x7b) {
                                value
                            } else {
                                val shift = insn.off.toInt() % 8 * 8
                                (destination.value[index] and (0xffff_ffffL shl shift).inv()) or ((value and 0xffff_ffffL) shl shift)
                            }
                        }
                        else -> error("unsupported store at $pc")
                    }
                    0x61, 0x79 -> {
                        val address = registers[insn.src] as? Value.Address
                        if (address != null && pointerSpills.containsKey(address.offset + insn.off)) {
                            check(insn.code == 0x79)
                            registers[insn.dst] = pointerSpills.getValue(address.offset + insn.off)
                            pc = next
                            continue
                        }
                        val value = when (val source = registers[insn.src]) {
                            is Value.Address -> stack[source.offset + insn.off]
                                ?: error("uninitialized stack at pc=$pc offset=${source.offset + insn.off} reads=$reads")
                            is Value.Context -> when (insn.off.toInt()) {
                                0 -> -1L
                                8 -> rawSyscall
                                104 -> boundaryIncomingFlags
                                112 -> rawSyscall
                                else -> error("context offset at $pc")
                            }
                            is Value.Entry -> if (boundaryMode) {
                                source.value[insn.off.toInt() / 8] ushr (if (insn.code == 0x61) insn.off.toInt() % 8 * 8 else 0)
                            } else {
                                when (insn.off.toInt()) {
                                4 -> if (source.map == 3 || source.map == 1) target.toLong() else source.value[0]
                                else -> source.value[insn.off.toInt() / 8]
                            }
                            }
                            else -> error("unsupported load at $pc")
                        }
                        registers[insn.dst] = if (insn.code == 0x61) {
                            Value.Number(value and 0xffff_ffffL)
                        } else {
                            if (sourceIsPtRegs(registers[insn.src], insn.off.toInt())) {
                                Value.PtRegs(0)
                            } else {
                                Value.Number(value)
                            }
                        }
                    }
                    0x15, 0x55, 0x35 -> {
                        val v = number(insn.dst)
                        val take = when (insn.code) {
                            0x15 -> v == insn.imm.toLong()
                            0x55 -> v != insn.imm.toLong()
                            else -> v >= insn.imm.toLong()
                        }
                        if (take) next += insn.off
                    }
                    0x5d -> if (number(insn.dst) != number(insn.src)) next += insn.off
                    0x1d -> if (number(insn.dst) == number(insn.src)) next += insn.off
                    0x3d -> if (java.lang.Long.compareUnsigned(number(insn.dst), number(insn.src)) >= 0) next += insn.off
                    0x05 -> next += insn.off
                    0x85 -> {
                         val returned: Value = when (insn.imm) {
                         5 -> Value.Number(123456)
                         131 -> Value.Number(0)
                        14 -> Value.Number((pid.toLong() shl 32) or 1L)
                         113 -> {
                             argumentReads++
                            reads++
                            if (reads == 1 && firstReadFails) {
                                Value.Number(-14)
                            } else {
                                stack[(registers[1] as Value.Address).offset] = if (reads == 1) 111L else secondReadFd.toLong()
                                Value.Number(0)
                            }
                        }
                         158 -> Value.Number(if (boundaryNoTask) 0 else 1)
                         156 -> if (boundaryMode) {
                             boundaryStorage?.let { Value.Entry(-1, 0, it) } ?: Value.Number(0)
                         } else if (markerTokenMissing) {
                             Value.Number(0)
                         } else {
                             Value.Entry(-1, 0, longArrayOf(if (markerTokenZero) 0 else 0x4d5a0000002aL))
                         }
                        2 -> {
                            val map = (registers[1] as Value.Map).index
                            val key = stack[(registers[2] as Value.Address).offset]?.toInt()
                            check(key == 42 && (map == 11 || rawProgram && map == 7))
                            if (!witnessInsertFails) witnessInserted = true
                            Value.Number(if (witnessInsertFails) -12 else 0)
                        }
                        1 -> {
                             val map = (registers[1] as Value.Map).index
                             val key = stack[(registers[2] as Value.Address).offset]?.toInt() ?: error("missing key at $pc")
                             if (boundaryMarker && map == 2) sessionLookups++
                             val cell = when (map) {
                                0 -> if (key == 111 || key == 222) LongArray(5) else null
                                1 -> LongArray(2)
                                2 -> if (boundaryMarker) {
                                    boundarySession
                                } else if (boundaryMode) {
                                    LongArray(1)
                                } else {
                                    null
                                }
                                3 -> if (boundaryMarker) {
                                    check(key in 0 until 32768)
                                    while (boundaryRows.size <= key) boundaryRows += LongArray(14)
                                    boundaryRows[key]
                                } else if (boundaryMode) {
                                    boundarySession
                                } else {
                                    LongArray(2)
                                }
                                12 -> {
                                    check(key in 0 until 32768)
                                    while (boundaryRows.size <= key) boundaryRows += LongArray(14)
                                    boundaryRows[key]
                                }
                                13 -> boundaryControl
                                4 -> if (boundaryMarker) boundaryControl else earlyCounters.getOrNull(key)
                                6 -> if (boundaryMode) LongArray(1) else null
                                9 -> earlyCounters.getOrNull(key)
                                5 -> if (boundaryMode) {
                                    metric
                                } else {
                                    when (key) {
                                    111 -> if (missingPreGateEntry) null else pre
                                    222 -> other
                                    else -> null
                                }
                                }
                                10 -> when (key) {
                                    111 -> if (missingPreGateEntry) null else pre
                                    222 -> other
                                    else -> null
                                }
                                7 -> LongArray(19)
                                11 -> witness
                                else -> null
                            }
                            val actual = if (map == 11 || rawProgram && map == 7 && key == 42) {
                                witness.takeIf { witnessInserted }
                            } else {
                                cell
                            }
                             if (actual == null ||
                                 boundaryMissingMap == (map to key) ||
                                 boundarySecondLookupMissing &&
                                     boundaryMarker &&
                                     map == 2 &&
                                 sessionLookups == 2
                             ) {
                                     Value.Number(0)
                                 } else {
                                     Value.Entry(map, key, actual)
                                 }
                        }
                        else -> error("unsupported helper ${insn.imm} at $pc")
                    }
                         (1..5).forEach { register -> registers[register] = Value.Unreadable }
                        registers[0] = returned
                    }
                    0xdb -> {
                        val entry = registers[insn.dst] as Value.Entry
                        val index = insn.off.toInt() / 8
                        val old = entry.value[index]
                        when (insn.imm) {
                            0, 1 -> {
                                entry.value[index] += number(insn.src)
                                if (insn.imm == 1) registers[insn.src] = Value.Number(old)
                            }
                            0x40 -> entry.value[index] = old or number(insn.src)
                            0xf1 -> {
                                if (old == number(0)) entry.value[index] = number(insn.src)
                                registers[0] = Value.Number(old)
                            }
                            else -> error("unsupported atomic at $pc")
                        }
                        if (boundaryMode && !boundaryMarker && entry.map == 5) {
                            check(boundaryControl[5] != 0L || boundaryRows.single()[0].toInt() == 3 && boundaryRows.single()[12].toInt() == 29) { "active metric preceded diagnostic checkpoint" }
                            return
                        }
                    }
                    0x95 -> return
                    else -> error("unsupported instruction ${insn.code.toString(16)} at $pc")
                }
                pc = next
            }
            error("diagnostic prefix did not reach the in-flight boundary: pc=$pc steps=$steps")
        }

        private fun sourceIsPtRegs(
            value: Value,
            offset: Int,
        ): Boolean = value == Value.Context && offset == 0
    }
}
