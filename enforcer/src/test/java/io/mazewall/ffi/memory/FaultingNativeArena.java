package io.mazewall.ffi.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/** Scoped native allocation faults; no global provider or production class is replaced. */
public final class FaultingNativeArena {
    private FaultingNativeArena() {}

    public static NativeArena failingAllocation(int ordinal, Runnable failure) {
        return new NativeArena(new ScriptedArena(ordinal, failure, 0), false);
    }

    public static NativeArena readOnlyAllocation(int ordinal) {
        return new NativeArena(new ScriptedArena(0, () -> {}, ordinal), false);
    }

    private static final class ScriptedArena implements Arena {
        private final Arena delegate = Arena.ofConfined();
        private final int failAt;
        private final Runnable failure;
        private final int readOnlyAt;
        private int allocations;

        private ScriptedArena(int failAt, Runnable failure, int readOnlyAt) {
            this.failAt = failAt;
            this.failure = failure;
            this.readOnlyAt = readOnlyAt;
        }

        @Override
        public MemorySegment allocate(long bytes, long alignment) {
            int ordinal = ++allocations;
            if (ordinal == failAt) failure.run();
            MemorySegment segment = delegate.allocate(bytes, alignment);
            return ordinal == readOnlyAt ? segment.asReadOnly() : segment;
        }

        @Override
        public MemorySegment.Scope scope() {
            return delegate.scope();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
