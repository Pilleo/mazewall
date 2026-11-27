package io.mazewall.platform.lifecycle

import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import java.lang.annotation.Inherited

internal const val PROCESS_DESCRIPTORS = "mazewall.test.process-descriptors"

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Inherited
@ResourceLock(PROCESS_DESCRIPTORS)
@ExtendWith(ForeignFdGuard::class)
internal annotation class GuardProcessDescriptors

/** Concurrent child-process probes share access until their parent descriptors are retired. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Inherited
@ResourceLock(PROCESS_DESCRIPTORS, mode = ResourceAccessMode.READ)
internal annotation class UsesProcessDescriptors
