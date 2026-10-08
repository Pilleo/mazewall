package demo.boot;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicReference;

/** Real filesystem publication/cleanup faults; no framework/native emulation. */
public final class CandidatePublicationTest {
    public static void main(String[] args) throws Exception {
        for (String name : new String[]{"candidate.json", "generation.txt"}) {
            for (String shape : new String[]{"file", "symlink", "dangling-symlink", "hardlink"}) existing(name, shape);
        }
        directoryAlias();
        secondPublicationCollision(false);
        secondPublicationCollision(true);
        cleanupPermissionFailure();
        atomicVisibility();
        System.out.println("CANDIDATE_PUBLICATION_FILESYSTEM_PASS 13 cases; genuine acquisition/no-clobber/owned cleanup/original failure");
    }

    private static void existing(String name, String shape) throws Exception {
        Path root = Files.createTempDirectory("task13-publication-existing-");
        Path external = root.resolve("operator.txt");
        Files.writeString(external, "unknown original");
        Path destination = root.resolve(name);
        switch (shape) {
            case "file" -> Files.writeString(destination, "unknown original");
            case "symlink" -> Files.createSymbolicLink(destination, external);
            case "dangling-symlink" -> Files.createSymbolicLink(destination, root.resolve("missing"));
            case "hardlink" -> Files.createLink(destination, external);
            default -> throw new AssertionError(shape);
        }
        Object key = Files.readAttributes(destination, "unix:ino", java.nio.file.LinkOption.NOFOLLOW_LINKS).get("ino");
        try { publish(root); throw new AssertionError("Unknown artifact accepted: " + shape); }
        catch (FileAlreadyExistsException expected) { /* Actual no-clobber rejection. */ }
        check(key.equals(Files.readAttributes(destination, "unix:ino", java.nio.file.LinkOption.NOFOLLOW_LINKS).get("ino")), "Unknown inode replaced");
        check(Files.readString(external).equals("unknown original"), "Symlink/alias target changed");
        if (!shape.equals("dangling-symlink")) check(Files.readString(destination).equals("unknown original"), "Unknown bytes changed");
        try (var entries = Files.list(root)) { check(entries.count() == 2, "Preflight left a partial pair/staging artifact"); }
    }

    private static void directoryAlias() throws Exception {
        Path parent = Files.createTempDirectory("task13-publication-directory-");
        Path real = Files.createDirectory(parent.resolve("real"));
        Path alias = Files.createSymbolicLink(parent.resolve("alias"), real);
        try { publish(alias); throw new AssertionError("Symlink directory accepted"); }
        catch (IOException rejected) { check(rejected.getMessage().contains("alias"), "Wrong directory failure"); }
        try (var entries = Files.list(real)) { check(entries.count() == 0, "Wrote through directory alias"); }
    }

    private static void secondPublicationCollision(boolean replaceFirst) throws Exception {
        Path root = Files.createTempDirectory("task13-publication-collision-");
        AtomicReference<IOException> original = new AtomicReference<>();
        var io = new CandidatePublication.FileOps() {
            int links;
            public void link(Path destination, Path source) throws IOException {
                if (++links == 2) {
                    Files.writeString(destination, "new operator artifact");
                    if (replaceFirst) {
                        Files.delete(root.resolve("candidate.json"));
                        Files.writeString(root.resolve("candidate.json"), "replacement operator artifact");
                    }
                }
                try { Files.createLink(destination, source); }
                catch (IOException actual) { original.set(actual); throw actual; }
            }
            public void delete(Path path) throws IOException { Files.delete(path); }
        };
        try { CandidatePublication.publish(root, "candidate.json", "complete document", "generation.txt", "complete generation", io); throw new AssertionError("Collision ignored"); }
        catch (IOException failure) {
            check(failure == original.get() && failure instanceof FileAlreadyExistsException, "Original actual link failure lost");
            check(Files.readString(root.resolve("generation.txt")).equals("new operator artifact"), "Independent second artifact destroyed");
            if (replaceFirst) {
                check(Files.readString(root.resolve("candidate.json")).equals("replacement operator artifact"), "Replaced first name deleted");
                check(failure.getSuppressed().length == 1, "Ownership refusal not retained on primary");
            } else check(!Files.exists(root.resolve("candidate.json")), "Exact owned first link not cleaned");
        }
    }

    private static void cleanupPermissionFailure() throws Exception {
        Path root = Files.createTempDirectory("task13-publication-denied-");
        AtomicReference<IOException> original = new AtomicReference<>();
        var io = new CandidatePublication.FileOps() {
            int links;
            public void link(Path destination, Path source) throws IOException {
                if (++links == 2) Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("r-x------"));
                try { Files.createLink(destination, source); }
                catch (IOException actual) { original.set(actual); throw actual; }
            }
            public void delete(Path path) throws IOException { Files.delete(path); }
        };
        try {
            CandidatePublication.publish(root, "candidate.json", "complete document", "generation.txt", "complete generation", io);
            throw new AssertionError("Required real permission fault unavailable (run as ordinary user)");
        } catch (IOException failure) {
            check(failure == original.get(), "Cleanup masked original link failure");
            check(failure.getSuppressed().length >= 2, "Independent real cleanup failures not retained");
            check(Files.readString(root.resolve("candidate.json")).equals("complete document"), "Partial owned bytes corrupted");
        } finally { Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------")); }
    }

    private static void atomicVisibility() throws Exception {
        Path root = Files.createTempDirectory("task13-publication-atomic-");
        String document = "whole document".repeat(10000);
        String generation = "whole generation".repeat(10000);
        AtomicReference<Path> stagedDocument = new AtomicReference<>();
        var io = new CandidatePublication.FileOps() {
            public void link(Path destination, Path source) throws IOException {
                check(!Files.exists(destination), "Visible output before publication");
                if (destination.getFileName().toString().equals("candidate.json")) stagedDocument.set(source);
                check(Files.readString(source).equals(destination.getFileName().toString().equals("candidate.json") ? document : generation), "Staging not fully written");
                Files.createLink(destination, source);
                check(Files.readString(destination).equals(Files.readString(source)), "Partial bytes published");
            }
            public void delete(Path path) throws IOException {
                Files.delete(path);
                if (path.equals(stagedDocument.get())) {
                    check(hasOpenDeletedAcquisition(path), "Genuine staging channel closed before identity-sensitive cleanup");
                }
            }
        };
        CandidatePublication.publish(root, "candidate.json", document, "generation.txt", generation, io);
        check(!hasOpenDeletedAcquisition(stagedDocument.get()), "Staging acquisition leaked after publication/cleanup");
        try (var entries = Files.list(root)) { check(entries.count() == 2, "Staging leaked on success"); }
        check(((Number) Files.getAttribute(root.resolve("candidate.json"), "unix:nlink")).intValue() == 1, "Stage alias retained");
        try { publish(root); throw new AssertionError("Existing successful pair overwritten"); }
        catch (FileAlreadyExistsException expected) { check(Files.readString(root.resolve("candidate.json")).equals(document), "Previous result changed"); }
    }

    private static void publish(Path root) throws IOException {
        CandidatePublication.publish(root, "candidate.json", "complete document", "generation.txt", "complete generation");
    }
    private static boolean hasOpenDeletedAcquisition(Path path) throws IOException {
        try (var descriptors = Files.list(Path.of("/proc/self/fd"))) {
            for (var descriptor : descriptors.toList()) {
                try {
                    if (Files.readSymbolicLink(descriptor).toString().equals(path + " (deleted)")) return true;
                } catch (java.nio.file.NoSuchFileException closedDuringListing) {
                    // Enumeration's own closed descriptor is not an ownership token or permission failure.
                }
            }
        }
        return false;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
