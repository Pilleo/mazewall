package io.mazewall.build;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Minimal class-file metadata reader used to select annotated tests without class loading. */
final class FreshJvmClassSelector {
    private static final String ANNOTATION_DESCRIPTOR = "Lio/mazewall/NeedsFreshJvm;";

    private FreshJvmClassSelector() {}

    static List<String> select(Iterable<File> roots) {
        Map<String, ClassMetadata> classes = new HashMap<>();
        for (File root : roots) {
            if (!root.isDirectory()) continue;
            try (var paths = Files.walk(root.toPath())) {
                paths.filter(path -> path.toString().endsWith(".class")).forEach(path -> {
                    try {
                        ClassMetadata metadata = read(path.toFile());
                        if (!metadata.className().endsWith(".NeedsFreshJvm")) classes.put(metadata.className(), metadata);
                    } catch (IOException failure) {
                        throw new IllegalStateException("Cannot inspect fresh-JVM test class " + path, failure);
                    }
                });
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot scan fresh-JVM test classes in " + root, failure);
            }
        }
        Set<String> selected = new LinkedHashSet<>();
        classes.values().stream().sorted(Comparator.comparing(ClassMetadata::className))
            .filter(metadata -> annotated(metadata, classes, new LinkedHashSet<>()))
            .map(ClassMetadata::className).forEach(selected::add);
        return List.copyOf(selected);
    }

    private static boolean annotated(ClassMetadata metadata, Map<String, ClassMetadata> classes, Set<String> visiting) {
        if (metadata.directlyAnnotated()) return true;
        if (metadata.superName() == null || !visiting.add(metadata.className())) return false;
        ClassMetadata parent = classes.get(metadata.superName());
        return parent != null && annotated(parent, classes, visiting);
    }

    private static ClassMetadata read(File file) throws IOException {
        try (DataInputStream input = new DataInputStream(Files.newInputStream(file.toPath()))) {
            if (input.readInt() != 0xCAFEBABE) throw new IOException("Invalid class-file magic");
            input.skipNBytes(4);
            Object[] pool = constantPool(input);
            input.readUnsignedShort();
            String name = className(pool, input.readUnsignedShort());
            int superIndex = input.readUnsignedShort();
            String parent = superIndex == 0 ? null : className(pool, superIndex);
            input.skipNBytes(input.readUnsignedShort() * 2L);
            skipMembers(input, pool, false);
            boolean methodAnnotated = skipMembers(input, pool, true);
            return new ClassMetadata(name, parent, methodAnnotated || attributesAnnotated(input, pool));
        }
    }

    private static Object[] constantPool(DataInputStream input) throws IOException {
        Object[] pool = new Object[input.readUnsignedShort()];
        for (int index = 1; index < pool.length; index++) {
            switch (input.readUnsignedByte()) {
                case 1 -> pool[index] = input.readUTF();
                case 7 -> pool[index] = input.readUnsignedShort();
                case 3, 4 -> input.skipNBytes(4);
                case 5, 6 -> { input.skipNBytes(8); index++; }
                case 8, 16, 19, 20 -> input.skipNBytes(2);
                case 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
                case 15 -> input.skipNBytes(3);
                default -> throw new IOException("Unsupported constant-pool entry");
            }
        }
        return pool;
    }

    private static String className(Object[] pool, int index) throws IOException {
        if (!(pool[index] instanceof Integer nameIndex) || !(pool[nameIndex] instanceof String name)) {
            throw new IOException("Invalid class name");
        }
        return name.replace('/', '.');
    }

    private static boolean skipMembers(DataInputStream input, Object[] pool, boolean inspect) throws IOException {
        boolean annotated = false;
        int members = input.readUnsignedShort();
        for (int member = 0; member < members; member++) {
            input.skipNBytes(6);
            int count = input.readUnsignedShort();
            for (int index = 0; index < count; index++) {
                String name = (String) pool[input.readUnsignedShort()];
                int length = input.readInt();
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length) throw new IOException("Truncated member attribute");
                if (inspect && annotationAttribute(name) && hasTargetAnnotation(bytes, pool)) annotated = true;
            }
        }
        return annotated;
    }

    private static boolean attributesAnnotated(DataInputStream input, Object[] pool) throws IOException {
        boolean annotated = false;
        int count = input.readUnsignedShort();
        for (int index = 0; index < count; index++) {
            String name = (String) pool[input.readUnsignedShort()];
            int length = input.readInt();
            byte[] bytes = input.readNBytes(length);
            if (bytes.length != length) throw new IOException("Truncated class attribute");
            if (annotationAttribute(name) && hasTargetAnnotation(bytes, pool)) annotated = true;
        }
        return annotated;
    }

    private static boolean annotationAttribute(String name) {
        return "RuntimeVisibleAnnotations".equals(name) || "RuntimeInvisibleAnnotations".equals(name);
    }

    private static boolean hasTargetAnnotation(byte[] bytes, Object[] pool) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int count = input.readUnsignedShort();
            for (int index = 0; index < count; index++) if (readAnnotation(input, pool)) return true;
            return false;
        }
    }

    private static boolean readAnnotation(DataInputStream input, Object[] pool) throws IOException {
        boolean target = ANNOTATION_DESCRIPTOR.equals(pool[input.readUnsignedShort()]);
        int pairs = input.readUnsignedShort();
        for (int index = 0; index < pairs; index++) {
            input.readUnsignedShort();
            skipElement(input, pool);
        }
        return target;
    }

    private static void skipElement(DataInputStream input, Object[] pool) throws IOException {
        switch (input.readUnsignedByte()) {
            case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' -> input.readUnsignedShort();
            case 'e' -> input.skipNBytes(4);
            case '@' -> readAnnotation(input, pool);
            case '[' -> {
                int count = input.readUnsignedShort();
                for (int index = 0; index < count; index++) skipElement(input, pool);
            }
            default -> throw new IOException("Invalid annotation element");
        }
    }

    private record ClassMetadata(String className, String superName, boolean directlyAnnotated) {}
}
