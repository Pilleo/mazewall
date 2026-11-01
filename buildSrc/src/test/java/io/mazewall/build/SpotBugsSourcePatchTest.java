package io.mazewall.build;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

public final class SpotBugsSourcePatchTest {
    public static void main(String[] args) throws Exception {
        var patch = new Properties();
        patch.setProperty("sha256", digest("before\n"));
        patch.setProperty("resultSha256", digest("after\n"));
        patch.setProperty("before.1", "before");
        patch.setProperty("after.1", "after");
        String result = new String(GenerateSpotBugsSources.reconstruct("before\n".getBytes(StandardCharsets.UTF_8), patch), StandardCharsets.UTF_8);
        if (!result.equals("after\n")) throw new AssertionError("Patch was not applied");
        rejects(() -> GenerateSpotBugsSources.reconstruct("changed upstream\n".getBytes(StandardCharsets.UTF_8), patch));
        patch.setProperty("before.1", "absent");
        rejects(() -> GenerateSpotBugsSources.reconstruct("before\n".getBytes(StandardCharsets.UTF_8), patch));
        patch.setProperty("before.1", "before");
        patch.setProperty("resultSha256", digest("unreviewed output"));
        rejects(() -> GenerateSpotBugsSources.reconstruct("before\n".getBytes(StandardCharsets.UTF_8), patch));
        System.out.println("SPOTBUGS_PATCH_TEST_OK");
    }

    private static String digest(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void rejects(Runnable action) {
        try { action.run(); } catch (org.gradle.api.GradleException expected) { return; }
        throw new AssertionError("Unreviewed source or patch accepted");
    }
}
