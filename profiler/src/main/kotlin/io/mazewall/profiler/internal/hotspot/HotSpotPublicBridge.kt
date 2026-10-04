package io.mazewall.profiler.internal.hotspot

import io.mazewall.platform.lifecycle.Pid
import io.mazewall.profiler.api.HotSpotOptions
import io.mazewall.profiler.api.HotSpotProfilingException
import io.mazewall.profiler.api.HotSpotProfilingSession as PublicSession

internal fun interface HotSpotSessionFactory {
    fun attach(
        pid: Pid,
        options: HotSpotOptions,
    ): PublicSession
}

internal object RealHotSpotSessionFactory : HotSpotSessionFactory {
    @Suppress("TooGenericExceptionCaught") // Admission has no child lease before the backend creates one; preserve the original cause.
    override fun attach(pid: Pid, options: HotSpotOptions): PublicSession = try {
        PublicSession(HotSpotBackend().attach(pid, options.internalOptions))
    } catch (failure: HotSpotSessionFailure) {
        throw HotSpotProfilingException.from(failure)
    } catch (failure: Exception) {
        throw HotSpotProfilingException("HotSpot admission refused", failure, null, null, emptyList(), "", 0)
    }
}
