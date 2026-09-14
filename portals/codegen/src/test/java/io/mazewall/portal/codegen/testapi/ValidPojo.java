package io.mazewall.portal.codegen.testapi;

public final class ValidPojo {
    private final String name;
    private final int count;

    public ValidPojo(String name, int count) {
        this.name = name;
        this.count = count;
    }

    public String getName() {
        return name;
    }

    public int count() {
        return count;
    }
}
