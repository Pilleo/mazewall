package demo.boot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** No-replace publication in an operator-controlled stable directory, never review authority. */
final class CandidatePublication {
    interface FileOps {
        void link(Path destination, Path source) throws IOException;
        void delete(Path path) throws IOException;
    }
    private static final FileOps NIO = new FileOps() {
        public void link(Path destination, Path source) throws IOException { Files.createLink(destination, source); }
        public void delete(Path path) throws IOException { Files.delete(path); }
    };
    private record Owned(Path path, Object key) {
        void validate() throws IOException {
            var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!key.equals(attributes.fileKey()) || attributes.isSymbolicLink()) {
                throw new IOException("Refusing cleanup/publication of replaced owned object: " + path);
            }
        }
    }

    static void publish(Path directory, String documentName, String document, String generationName, String generation) throws IOException {
        publish(directory, documentName, document, generationName, generation, NIO);
    }

    // Narrow I/O boundary allows real-filesystem failure injection, not simulated native success.
    static void publish(Path directory, String documentName, String document, String generationName, String generation,
                        FileOps io) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        rejectDirectoryAliases(root);
        requireLeaf(documentName);
        requireLeaf(generationName);
        if (documentName.equals(generationName)) throw new IllegalArgumentException("Distinct publication names required");
        var rootOwner = new Owned(root, key(root));
        // Both unknown output names are checked before acquiring/writing any new artifact.
        requireAbsent(root.resolve(documentName));
        requireAbsent(root.resolve(generationName));
        try (var rootStream = secure(root)) {
            if (!rootOwner.key().equals(rootStream.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey())) {
                throw new IOException("Publication directory changed during acquisition");
            }
            Path stage = root.resolve(".measurement-" + UUID.randomUUID());
            rootOwner.validate();
            Files.createDirectory(stage, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            var stageOwner = new Owned(stage, key(stage));
            List<Owned> staged = new ArrayList<>();
            List<Owned> published = new ArrayList<>();
            List<SeekableByteChannel> acquisitions = new ArrayList<>();
            Throwable failure = null;
            try (var stageStream = secure(stage)) {
                rejectDirectoryAliases(stage);
                if (!stageOwner.key().equals(stageStream.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey())) {
                    throw new IOException("Staging directory changed during acquisition");
                }
                stageFile(stageStream, stage, "document", document, staged, acquisitions);
                stageFile(stageStream, stage, "generation", generation, staged, acquisitions);
                String[] names = {documentName, generationName};
                for (int index = 0; index < names.length; index++) {
                    rejectDirectoryAliases(root);
                    rootOwner.validate();
                    stageOwner.validate();
                    var source = staged.get(index);
                    source.validate();
                    if (((Number) Files.getAttribute(source.path(), "unix:nlink", LinkOption.NOFOLLOW_LINKS)).intValue() != 1) {
                        throw new IOException("Staged artifact has an unowned hardlink alias: " + source.path());
                    }
                    Path destination = root.resolve(names[index]);
                    // link(2) exposes only complete forced bytes, atomically and with no replacement.
                    // Existing files, hardlinks and even dangling symlinks all fail EEXIST.
                    io.link(destination, source.path());
                    var owner = new Owned(destination, source.key());
                    published.add(owner);
                    owner.validate();
                }
            } catch (IOException | RuntimeException | Error problem) {
                failure = problem;
            }
            for (var owner : staged) failure = remove(owner, rootOwner, io, failure);
            failure = remove(stageOwner, rootOwner, io, failure);
            if (failure != null) {
                for (var owner : published) failure = remove(owner, rootOwner, io, failure);
            }
            // Keep the genuine open acquisitions through all identity-sensitive unlink
            // decisions: a removed/replaced name cannot recycle its original inode key.
            for (var channel : acquisitions) {
                try { channel.close(); }
                catch (IOException | RuntimeException | Error cleanup) {
                    if (failure == null) failure = cleanup;
                    else if (failure != cleanup) failure.addSuppressed(cleanup);
                }
            }
            if (failure != null) rethrow(failure);
        }
    }

    private static void stageFile(SecureDirectoryStream<Path> directory, Path root, String name, String content,
                                  List<Owned> owners, List<SeekableByteChannel> acquisitions) throws IOException {
        var channel = directory.newByteChannel(Path.of(name),
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        acquisitions.add(channel);
        // This token follows a genuine exclusive acquisition; never wrap an invented descriptor.
        var attributes = directory.getFileAttributeView(Path.of(name), BasicFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS).readAttributes();
        var owner = new Owned(root.resolve(name), requireKey(attributes.fileKey()));
        owners.add(owner);
        ByteBuffer bytes = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
        while (bytes.hasRemaining()) channel.write(bytes);
        if (!(channel instanceof FileChannel file)) throw new IOException("Required genuine forceable file channel unavailable");
        file.force(true);
        owner.validate();
    }

    private static Throwable remove(Owned owner, Owned root, FileOps io, Throwable primary) {
        try {
            rejectDirectoryAliases(root.path());
            rejectDirectoryAliases(owner.path().getParent());
            root.validate();
            owner.validate(); // A replaced name is never permission to delete its new occupant.
            io.delete(owner.path());
        } catch (IOException | RuntimeException | Error cleanup) {
            if (primary == null) return cleanup;
            if (cleanup != primary) primary.addSuppressed(cleanup);
        }
        return primary;
    }

    static void rejectDirectoryAliases(Path root) throws IOException {
        Path current = root.getRoot();
        for (Path part : root) {
            current = current.resolve(part);
            var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                throw new IOException("Measurement directory contains a symlink/non-directory alias: " + current);
            }
        }
    }

    private static SecureDirectoryStream<Path> secure(Path root) throws IOException {
        var stream = Files.newDirectoryStream(root);
        if (stream instanceof SecureDirectoryStream<Path> secure) return secure;
        stream.close();
        throw new IOException("Required secure directory acquisition unavailable: " + root);
    }
    private static Object key(Path path) throws IOException {
        return requireKey(Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey());
    }
    private static Object requireKey(Object key) throws IOException {
        if (key == null) throw new IOException("Required filesystem object identity unavailable");
        return key;
    }
    private static void requireLeaf(String name) {
        Path leaf = Path.of(name);
        if (name.isBlank() || leaf.isAbsolute() || leaf.getNameCount() != 1 || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("Single publication filename required: " + name);
        }
    }
    private static void requireAbsent(Path path) throws IOException {
        try { Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (java.nio.file.NoSuchFileException absent) { return; }
        throw new FileAlreadyExistsException(path.toString());
    }
    private static void rethrow(Throwable failure) throws IOException {
        if (failure instanceof IOException io) throw io;
        if (failure instanceof RuntimeException runtime) throw runtime;
        throw (Error) failure;
    }
}
