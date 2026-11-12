package io.mazewall.ffi.internal;

public final class OwnedStackScopingPackagedWorkload {
    public OwnedStackScopingPackagedWorkload() {}

    public int call() { return 42; }

    public static int marker() { return 42; }
}
