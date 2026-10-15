package io.mazewall.profiler.internal.engine

import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.profiler.api.ConnectResolution
import io.mazewall.profiler.internal.SockaddrDecoder

/** Remote reads stay in the owned preparation helper; decoding itself is heap-only and pure. */
internal class ConnectAddressResolver(
    private val memory: ProfilerMemoryReader,
) {
    context(arena: NativeArena) fun resolve(
        tid: Tid,
        name: String,
        args: List<Long>,
    ): ConnectResolution {
        if (!name.equals("CONNECT", ignoreCase = true)) return ConnectResolution.NotApplicable
        val address = args.getOrNull(1)
        // connect(2)'s addrlen is a C int; the upper register bits are not part of it.
        val length = args.getOrNull(2)?.toInt()
        if (address == null || address == 0L || length == null || length !in 2..SockaddrDecoder.MAX_BYTES) {
            return ConnectResolution.Failed(ConnectResolution.Reason.INVALID_ARGUMENTS, "CONNECT has no bounded socket address")
        }
        return inspect(tid, address, length)
    }

    context(arena: NativeArena) private fun inspect(
        tid: Tid,
        address: Long,
        length: Int,
    ): ConnectResolution {
        val bytes = try {
            memory.readBytes(tid, address, length)
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (failure: IllegalStateException) {
            return ConnectResolution.Failed(ConnectResolution.Reason.MEMORY_READ_FAILED, "CONNECT memory inspection: ${failure.javaClass.name}: ${failure.message.orEmpty().take(1024)}")
        }
        return if (bytes == null || bytes.size != length) {
            ConnectResolution.Failed(ConnectResolution.Reason.TRUNCATED, "CONNECT socket address read was absent or partial")
        } else {
            SockaddrDecoder.decode(bytes)
        }
    }
}
