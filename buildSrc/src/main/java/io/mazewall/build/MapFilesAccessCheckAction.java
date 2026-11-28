package io.mazewall.build;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Task;

/** Checks the executing host without storing probe results or capturing a build script. */
public final class MapFilesAccessCheckAction implements Action<Task> {
    @Override
    public void execute(Task task) {
        try {
            if (mapFilesOpenDenied()) {
                throw new GradleException(
                    "Lane ALL cannot open /proc/self/map_files on this host. "
                        + "From the checkout root, ./gradlew build runs BASELINE here and CONFINED in Docker. "
                        + "Host only: -PmazewallKernelLane=BASELINE");
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not check /proc/self/map_files access for " + task.getPath(), failure);
        }
    }

    private static boolean mapFilesOpenDenied() throws IOException {
        Path directory = Path.of("/proc/self/map_files");
        if (!Files.isDirectory(directory)) return false;
        Path entry;
        try (var stream = Files.newDirectoryStream(directory)) {
            var entries = stream.iterator();
            if (!entries.hasNext()) return false;
            entry = entries.next();
        }
        try {
            Files.newByteChannel(entry, StandardOpenOption.READ).close();
            return false;
        } catch (FileSystemException failure) {
            String reason = failure.getReason() == null ? failure.getMessage() : failure.getReason();
            return reason != null && (reason.contains("not permitted") || reason.contains("Permission denied"));
        }
    }
}
