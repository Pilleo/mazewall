package io.mazewall.spring.fixture;

/** Shared parent-loader API deliberately has no Spring dependency. */
public interface SharedOperation {
    String run();
}
