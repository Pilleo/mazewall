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
    private static final String FRESH_JVM_DESCRIPTOR = "Lio/mazewall/NeedsFreshJvm;";
    private static final String JUNIT_TEST_DESCRIPTOR = "Lorg/junit/jupiter/api/Test;";
    private static final int ACC_PRIVATE = 0x0002;
    private static final int ACC_STATIC = 0x0008;

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
        if (metadata.classAnnotated() || metadata.methods().stream().anyMatch(MethodMetadata::freshJvmAnnotated)) return true;
        if (metadata.superName() == null || !visiting.add(metadata.className())) return false;
        ClassMetadata parent = classes.get(metadata.superName());
        if (parent == null) return false;
        Set<String> overridden = metadata.methods().stream()
            .filter(MethodMetadata::canOverride)
            .map(MethodMetadata::signature)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return inheritedAnnotation(parent, classes, visiting, overridden);
    }

    private static boolean inheritedAnnotation(
        ClassMetadata metadata,
        Map<String, ClassMetadata> classes,
        Set<String> visiting,
        Set<String> overridden
    ) {
        if (metadata.classAnnotated()) return true;
        if (metadata.methods().stream().anyMatch(method -> method.inheritedFreshJvmTest() && !overridden.contains(method.signature()))) {
            return true;
        }
        if (metadata.superName() == null || !visiting.add(metadata.className())) return false;
        metadata.methods().stream().filter(MethodMetadata::canOverride).map(MethodMetadata::signature).forEach(overridden::add);
        ClassMetadata parent = classes.get(metadata.superName());
        return parent != null && inheritedAnnotation(parent, classes, visiting, overridden);
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
            skipMembers(input, pool);
            List<MethodMetadata> methods = readMethods(input, pool);
            return new ClassMetadata(name, parent, attributesAnnotated(input, pool), List.copyOf(methods));
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

    private static void skipMembers(DataInputStream input, Object[] pool) throws IOException {
        int members = input.readUnsignedShort();
        for (int member = 0; member < members; member++) {
            input.skipNBytes(6);
            int count = input.readUnsignedShort();
            for (int index = 0; index < count; index++) {
                String name = (String) pool[input.readUnsignedShort()];
                int length = input.readInt();
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length) throw new IOException("Truncated member attribute");
            }
        }
    }

    private static List<MethodMetadata> readMethods(DataInputStream input, Object[] pool) throws IOException {
        List<MethodMetadata> methods = new java.util.ArrayList<>();
        int members = input.readUnsignedShort();
        for (int member = 0; member < members; member++) {
            int access = input.readUnsignedShort();
            String name = (String) pool[input.readUnsignedShort()];
            String descriptor = (String) pool[input.readUnsignedShort()];
            boolean freshJvm = false;
            boolean junitTest = false;
            int count = input.readUnsignedShort();
            for (int index = 0; index < count; index++) {
                String attribute = (String) pool[input.readUnsignedShort()];
                int length = input.readInt();
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length) throw new IOException("Truncated member attribute");
                if (annotationAttribute(attribute)) {
                    freshJvm |= hasAnnotation(bytes, pool, FRESH_JVM_DESCRIPTOR);
                    junitTest |= hasAnnotation(bytes, pool, JUNIT_TEST_DESCRIPTOR);
                }
            }
            methods.add(new MethodMetadata(name + descriptor, freshJvm, junitTest, (access & (ACC_PRIVATE | ACC_STATIC)) == 0));
        }
        return methods;
    }

    private static boolean attributesAnnotated(DataInputStream input, Object[] pool) throws IOException {
        boolean annotated = false;
        int count = input.readUnsignedShort();
        for (int index = 0; index < count; index++) {
            String name = (String) pool[input.readUnsignedShort()];
            int length = input.readInt();
            byte[] bytes = input.readNBytes(length);
            if (bytes.length != length) throw new IOException("Truncated class attribute");
            if (annotationAttribute(name) && hasAnnotation(bytes, pool, FRESH_JVM_DESCRIPTOR)) annotated = true;
        }
        return annotated;
    }

    private static boolean annotationAttribute(String name) {
        return "RuntimeVisibleAnnotations".equals(name) || "RuntimeInvisibleAnnotations".equals(name);
    }

    private static boolean hasAnnotation(byte[] bytes, Object[] pool, String descriptor) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int count = input.readUnsignedShort();
            boolean found = false;
            for (int index = 0; index < count; index++) found |= readAnnotation(input, pool, descriptor);
            return found;
        }
    }

    private static boolean readAnnotation(DataInputStream input, Object[] pool, String descriptor) throws IOException {
        boolean target = descriptor.equals(pool[input.readUnsignedShort()]);
        int pairs = input.readUnsignedShort();
        for (int index = 0; index < pairs; index++) {
            input.readUnsignedShort();
            skipElement(input, pool, descriptor);
        }
        return target;
    }

    private static void skipElement(DataInputStream input, Object[] pool, String descriptor) throws IOException {
        switch (input.readUnsignedByte()) {
            case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' -> input.readUnsignedShort();
            case 'e' -> input.skipNBytes(4);
            case '@' -> readAnnotation(input, pool, descriptor);
            case '[' -> {
                int count = input.readUnsignedShort();
                for (int index = 0; index < count; index++) skipElement(input, pool, descriptor);
            }
            default -> throw new IOException("Invalid annotation element");
        }
    }

    private record ClassMetadata(String className, String superName, boolean classAnnotated, List<MethodMetadata> methods) {}

    private record MethodMetadata(String signature, boolean freshJvmAnnotated, boolean junitTestAnnotated, boolean canOverride) {
        boolean inheritedFreshJvmTest() {
            return freshJvmAnnotated && junitTestAnnotated && canOverride;
        }
    }
}
