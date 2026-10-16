package io.mazewall.profiler.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Loaded only after the real required installation; ordinary tiered HotSpot compiles hot. */
public final class Task5aJitTarget {
    private Task5aJitTarget() {}

    public static long run() throws IOException {
        try (var resource = Task5aJitTarget.class.getResourceAsStream("/task5a-lazy.txt")) {
            if (resource == null || !new String(resource.readAllBytes(), StandardCharsets.UTF_8).equals("lazy-resource-after-install\n")) {
                throw new IOException("Actual lazy resource did not load under the installed policy");
            }
        }
        long result = 0;
        for (int i = 0; i < 250_000; i++) {
            result += hot(i);
        }
        return result;
    }

    private static int hot(int value) {
        return (value * 31) ^ (value >>> 3);
    }
}
