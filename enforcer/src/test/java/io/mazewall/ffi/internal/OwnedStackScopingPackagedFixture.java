package io.mazewall.ffi.internal;

public final class OwnedStackScopingPackagedFixture {
    private OwnedStackScopingPackagedFixture() {}

    public static final class DelegatingLoader extends ClassLoader {
        public DelegatingLoader(ClassLoader parent) { super(parent); }
    }

    public static void main(String[] args) {
        OwnedStackScopingPackagedBridge.run(args);
    }
}
