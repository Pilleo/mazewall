package io.mazewall.tierE.shim

/**
 * Stateless binding seam over the BPF object lifecycle (libbpf today, pure
 * syscalls in WP-14). Contains no policy: the daemon above it owns every
 * lifecycle and trust decision. Implementations must be safe to call from the
 * daemon's session thread only.
 */
public interface TierEBpfShim {
    /** Loads and verifies `context_probe.bpf.o`, creating a FRESH map set —
     *  one object per call, never recycled across epochs (invariant 5). */
    public fun loadObject(bpfObjectPath: String): Long

    public fun setTargetTgid(handle: Long, tgid: Int)

    public fun attachSysEnter(handle: Long)

    /** Attaches the plain symbol uprobe (production path per §4.1.1 sign-off). */
    public fun attachMarkerUprobe(handle: Long, pid: Int, sharedObjectPath: String)

    /** Parity-only until the Kotlin stapsdt parser lands. */
    public fun attachMarkerUsdt(handle: Long, pid: Int, sharedObjectPath: String)

    /** FD of the `context_events` ring buffer for mmap consumption. */
    public fun ringFd(handle: Long): Int

    public fun droppedTotal(handle: Long): ULong

    /** Destroys links + object: the whole epoch dies with these FDs. */
    public fun destroy(handle: Long)
}
