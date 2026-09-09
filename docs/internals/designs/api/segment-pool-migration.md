# SegmentPool lease migration

`SegmentPool` rentals now represent exclusive ownership of one checkout generation.
This is a scoped breaking API change: public constructors and `release(ManagedSegment)`
have been removed, and `rent()` returns `SegmentLease` rather than `ManagedSegment`.

Create and close a pool that owns its shared arena:

```kotlin
SegmentPool.owned(Layouts.SECCOMP_NOTIF, poolSize = 16).use { pool ->
    pool.rent().use { lease ->
        val notification = lease.segment
        // Consume the notification within this lease's lifetime.
    }
}
```

When a caller owns the arena, use `SegmentPool.borrowed(layout, arena, poolSize)` or
`SegmentPool.borrowed(byteSize, arena, poolSize)`. Closing a borrowed pool does not
close that arena. The arena must remain alive while rentals are in use, and confined
arenas retain their thread restrictions. `owned(byteSize, poolSize)` is also available.
Sizes must be positive and preallocation counts nonnegative.

A lease's `close()` is idempotent. Its checked `segment` accessor rejects use after
return, including when another lease has rented the same allocation. A return validates
the originating pool and exact lease identity/generation, never merely the native
address. A previously retrieved `ManagedSegment` remains a native alias: do not retain
it beyond the lease, hand it to asynchronous work that outlives the lease, or race its
use with `close()`. The accessor cannot revoke such aliases.

Closing a pool with outstanding leases throws without changing pool state or closing
its arena. Return those leases and retry. Successful close is idempotent and rejects
future rentals. Owned pools then close their arena; borrowed pools only retire their
own bookkeeping. Returned overflow allocations are retained for later rentals, so
`poolSize` specifies initial allocation rather than a memory cap.

`SECCOMP_NOTIF_POOL` and `SECCOMP_NOTIF_RESP_POOL` intentionally live for the process
lifetime. Session handlers acquire both leases and their confined ACK storage together,
roll back partial acquisition, and close only their own leases/arena during teardown.
