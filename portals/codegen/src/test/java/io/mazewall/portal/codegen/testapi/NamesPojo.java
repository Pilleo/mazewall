package io.mazewall.portal.codegen.testapi;

import java.util.List;

public class NamesPojo {
    private final List<String> values;

    public NamesPojo(List<String> values) {
        this.values = values;
    }

    public List<String> getValues() {
        return values;
    }
}
