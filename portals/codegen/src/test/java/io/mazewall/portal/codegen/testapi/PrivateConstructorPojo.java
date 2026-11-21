package io.mazewall.portal.codegen.testapi;

public class PrivateConstructorPojo {
    private final int count;

    private PrivateConstructorPojo(int count) {
        this.count = count;
    }

    public int getCount() {
        return count;
    }
}
