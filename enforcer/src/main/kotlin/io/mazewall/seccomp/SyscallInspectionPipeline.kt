package io.mazewall.seccomp

import io.mazewall.core.Arch

/**
 * Pipeline to execute and build syscall inspections.
 * Segregates the inspection execution logic from the central BpfFilter compiler.
 */
internal interface SyscallInspectionPipeline {
    val inspectors: List<SyscallInspector>
    fun getInspections(arch: Arch, context: InspectionContext): List<SyscallInspection>
    fun emitSpecial(
        builder: BpfBuilder<BpfState.NrLoaded>,
        arch: Arch,
        context: InspectionContext,
        handledNrs: MutableSet<Int>
    )
}

/**
 * Default implementation of the SyscallInspectionPipeline.
 */
internal class DefaultSyscallInspectionPipeline(
    override val inspectors: List<SyscallInspector>
) : SyscallInspectionPipeline {
    override fun getInspections(arch: Arch, context: InspectionContext): List<SyscallInspection> {
        return inspectors.flatMap { it.getInspections(arch, context) }
    }

    override fun emitSpecial(
        builder: BpfBuilder<BpfState.NrLoaded>,
        arch: Arch,
        context: InspectionContext,
        handledNrs: MutableSet<Int>
    ) {
        for (inspector in inspectors) {
            inspector.emitSpecial(builder, arch, context, handledNrs)
        }
    }
}
