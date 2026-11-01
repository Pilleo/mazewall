package io.mazewall.build;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/** Minimal class-file metadata reader used to select annotated tests without class loading. */
final class FreshJvmClassSelector {
    private enum Discovery { FRESH_JVM, ALL_JUNIT }
    private static final Set<String> FRESH_JVM_DESCRIPTORS = Set.of(
        "Lio/mazewall/NeedsFreshJvm;",
        "Lio/mazewall/enforcer/api/NeedsFreshJvm;"
    );
    private static final Set<String> JUNIT_TEST_DESCRIPTORS = Set.of(
        "Lorg/junit/jupiter/api/Test;", "Lorg/junit/jupiter/api/RepeatedTest;",
        "Lorg/junit/jupiter/api/TestTemplate;", "Lorg/junit/jupiter/api/TestFactory;",
        "Lorg/junit/jupiter/params/ParameterizedTest;"
    );
    private static final String NESTED_DESCRIPTOR = "Lorg/junit/jupiter/api/Nested;";
    private static final int ACC_PRIVATE = 0x0002;
    private static final int ACC_STATIC = 0x0008;
    private static final int ACC_ABSTRACT = 0x0400;
    private static final int ACC_INTERFACE = 0x0200;

    private FreshJvmClassSelector() {}

    static List<String> select(Iterable<File> roots) {
        return select(roots, Discovery.FRESH_JVM, List.of());
    }

    static List<String> selectTests(Iterable<File> roots, Iterable<File> classpath) {
        return select(roots, Discovery.ALL_JUNIT, classpath);
    }

    private static List<String> select(Iterable<File> roots, Discovery discovery, Iterable<File> classpath) {
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
            .filter(FreshJvmClassSelector::concrete)
            .filter(metadata -> !metadata.nested())
            .filter(metadata -> hasFreshTestTree(metadata, classes, discovery == Discovery.ALL_JUNIT, new LinkedHashSet<>(), discovery, classpath))
            .map(ClassMetadata::className).forEach(selected::add);
        if (selected.isEmpty() && discovery == Discovery.FRESH_JVM) {
            List<String> declarations = classes.values().stream()
                .filter(metadata -> metadata.classAnnotated() || metadata.methods().stream().anyMatch(MethodMetadata::freshJvmAnnotated))
                .map(ClassMetadata::className).sorted().toList();
            if (!declarations.isEmpty()) {
                throw new IllegalStateException("Declared @NeedsFreshJvm but found no executable JUnit tests in " + declarations
                    + ". Add a supported JUnit test annotation and a concrete test class; check testClassesDirs and inherited test outputs.");
            }
        }
        return selected.stream().sorted().toList();
    }

    private static boolean concrete(ClassMetadata metadata) {
        return (metadata.access() & (ACC_ABSTRACT | ACC_INTERFACE)) == 0;
    }

    /** Evaluate nested tests in the concrete discovery context, including inherited containers. */
    private static boolean hasFreshTestTree(
        ClassMetadata metadata,
        Map<String, ClassMetadata> classes,
        boolean enclosingTagged,
        Set<String> visiting,
        Discovery discovery,
        Iterable<File> classpath
    ) {
        if (!visiting.add(metadata.className())) return false;
        boolean tagged = enclosingTagged || classTagged(metadata, classes, new LinkedHashSet<>());
        if (hasFreshTest(metadata, classes, tagged)) return true;
        if (discovery == Discovery.ALL_JUNIT && mightHaveTests(metadata, classes, classpath, new LinkedHashSet<>())) return true;
        // JUnit inherits @Nested members from abstract and concrete bases.
        // Their enclosing instance is the current discovery class, so emitting
        // the lexical base would skip subclass-only nested test executions.
        Set<String> containers = new LinkedHashSet<>();
        ClassMetadata container = metadata;
        while (container != null && containers.add(container.className())) {
            container = classes.get(container.superName());
        }
        return classes.values().stream()
            .filter(ClassMetadata::nested)
            .filter(FreshJvmClassSelector::concrete)
            .filter(nested -> containers.contains(enclosingName(nested)))
            .anyMatch(nested -> hasFreshTestTree(nested, classes, tagged, new LinkedHashSet<>(visiting), discovery, classpath));
    }

    /** Unknown annotations or external class ancestry belong to Jupiter, not a negative metadata guess. */
    private static boolean mightHaveTests(ClassMetadata metadata, Map<String, ClassMetadata> classes, Iterable<File> classpath, Set<String> visiting) {
        if (!visiting.add(metadata.className())) return false;
        if (metadata.runtimeAnnotated() || metadata.methods().stream().anyMatch(method -> method.canOverride() && method.runtimeAnnotated())) return true;
        if (metadata.superName() != null && !metadata.superName().equals("java.lang.Object")) {
            ClassMetadata parent = classes.get(metadata.superName());
            if (parent == null || mightHaveTests(parent, classes, classpath, visiting)) return true;
        }
        for (String name : metadata.interfaces()) {
            ClassMetadata parent = classes.get(name);
            if (parent == null) parent = findClass(name, classpath);
            if (parent == null || mightHaveTests(parent, classes, classpath, visiting)) return true;
        }
        return false;
    }

    private static ClassMetadata findClass(String name, Iterable<File> classpath) {
        String entryName = name.replace('.', '/') + ".class";
        for (File entry : classpath) {
            try {
                if (entry.isDirectory()) {
                    File file = new File(entry, entryName);
                    if (file.isFile()) return read(file);
                } else if (entry.isFile() && entry.getName().endsWith(".jar")) {
                    try (JarFile jar = new JarFile(entry)) {
                        var member = jar.getJarEntry(entryName);
                        if (member != null) return jar.isMultiRelease() ? null : read(jar.getInputStream(member));
                    }
                }
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot inspect JUnit ancestor " + name + " in " + entry, failure);
            }
        }
        try (InputStream platform = ClassLoader.getPlatformClassLoader().getResourceAsStream(entryName)) {
            return platform == null ? null : read(platform);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot inspect platform JUnit ancestor " + name, failure);
        }
    }

    private static boolean hasFreshTest(ClassMetadata metadata, Map<String, ClassMetadata> classes, boolean tagged) {
        Map<String, MethodMetadata> methods = new java.util.LinkedHashMap<>();
        collectMethods(metadata, classes, new LinkedHashSet<>(), methods);
        return methods.values().stream().anyMatch(method -> method.junitTestAnnotated()
            && method.canOverride() && (tagged || method.freshJvmAnnotated()));
    }

    private static boolean classTagged(ClassMetadata metadata, Map<String, ClassMetadata> classes, Set<String> visiting) {
        if (metadata == null || !visiting.add(metadata.className())) return false;
        if (metadata.classAnnotated()) return true;
        return classTagged(classes.get(metadata.superName()), classes, visiting)
            || metadata.interfaces().stream().anyMatch(name -> classTagged(classes.get(name), classes, visiting))
            || (metadata.nested() && classTagged(classes.get(enclosingName(metadata)), classes, visiting));
    }

    private static void collectMethods(
        ClassMetadata metadata,
        Map<String, ClassMetadata> classes,
        Set<String> visiting,
        Map<String, MethodMetadata> methods
    ) {
        if (metadata == null || !visiting.add(metadata.className())) return;
        metadata.methods().stream().filter(MethodMetadata::canOverride)
            .forEach(method -> methods.putIfAbsent(method.signature(), method));
        collectMethods(classes.get(metadata.superName()), classes, visiting, methods);
        metadata.interfaces().forEach(name -> collectMethods(classes.get(name), classes, visiting, methods));
    }

    private static String enclosingName(ClassMetadata metadata) {
        int separator = metadata.className().lastIndexOf('$');
        return separator < 0 ? "" : metadata.className().substring(0, separator);
    }

    private static ClassMetadata read(File file) throws IOException {
        return read(Files.newInputStream(file.toPath()));
    }

    private static ClassMetadata read(InputStream source) throws IOException {
        try (DataInputStream input = new DataInputStream(source)) {
            if (input.readInt() != 0xCAFEBABE) throw new IOException("Invalid class-file magic");
            input.skipNBytes(4);
            Object[] pool = constantPool(input);
            int access = input.readUnsignedShort();
            String name = className(pool, input.readUnsignedShort());
            int superIndex = input.readUnsignedShort();
            String parent = superIndex == 0 ? null : className(pool, superIndex);
            int interfaceCount = input.readUnsignedShort();
            List<String> interfaces = new java.util.ArrayList<>(interfaceCount);
            for (int index = 0; index < interfaceCount; index++) interfaces.add(className(pool, input.readUnsignedShort()));
            skipMembers(input, pool);
            List<MethodMetadata> methods = readMethods(input, pool);
            Set<String> annotations = classAnnotations(input, pool);
            return new ClassMetadata(name, parent, List.copyOf(interfaces), annotations.stream().anyMatch(FRESH_JVM_DESCRIPTORS::contains),
                annotations.contains(NESTED_DESCRIPTOR), access, List.copyOf(methods),
                annotations.stream().anyMatch(annotation -> !annotation.equals("Lkotlin/Metadata;")
                    && !annotation.equals(NESTED_DESCRIPTOR) && !FRESH_JVM_DESCRIPTORS.contains(annotation)));
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
            boolean runtimeAnnotated = false;
            int count = input.readUnsignedShort();
            for (int index = 0; index < count; index++) {
                String attribute = (String) pool[input.readUnsignedShort()];
                int length = input.readInt();
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length) throw new IOException("Truncated member attribute");
                if (annotationAttribute(attribute)) {
                    if (attribute.equals("RuntimeVisibleAnnotations")) runtimeAnnotated |= !annotationTypes(bytes, pool).isEmpty();
                    freshJvm |= hasAnyAnnotation(bytes, pool, FRESH_JVM_DESCRIPTORS);
                    for (String descriptorName : JUNIT_TEST_DESCRIPTORS) junitTest |= hasAnnotation(bytes, pool, descriptorName);
                }
            }
            methods.add(new MethodMetadata(name + descriptor, freshJvm, junitTest, (access & (ACC_PRIVATE | ACC_STATIC)) == 0, runtimeAnnotated));
        }
        return methods;
    }

    private static Set<String> classAnnotations(DataInputStream input, Object[] pool) throws IOException {
        Set<String> annotations = new LinkedHashSet<>();
        int count = input.readUnsignedShort();
        for (int index = 0; index < count; index++) {
            String name = (String) pool[input.readUnsignedShort()];
            int length = input.readInt();
            byte[] bytes = input.readNBytes(length);
            if (bytes.length != length) throw new IOException("Truncated class attribute");
            if (annotationAttribute(name)) {
                if (name.equals("RuntimeVisibleAnnotations")) annotations.addAll(annotationTypes(bytes, pool));
                if (hasAnyAnnotation(bytes, pool, FRESH_JVM_DESCRIPTORS)) annotations.addAll(FRESH_JVM_DESCRIPTORS);
                if (hasAnnotation(bytes, pool, NESTED_DESCRIPTOR)) annotations.add(NESTED_DESCRIPTOR);
            }
        }
        return Set.copyOf(annotations);
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

    private static boolean hasAnyAnnotation(byte[] bytes, Object[] pool, Set<String> descriptors) throws IOException {
        for (String descriptor : descriptors) {
            if (hasAnnotation(bytes, pool, descriptor)) return true;
        }
        return false;
    }

    private static boolean readAnnotation(DataInputStream input, Object[] pool, String descriptor) throws IOException {
        return descriptor.equals(readAnnotationType(input, pool));
    }

    private static Set<String> annotationTypes(byte[] bytes, Object[] pool) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int count = input.readUnsignedShort();
            Set<String> types = new LinkedHashSet<>();
            for (int index = 0; index < count; index++) types.add(readAnnotationType(input, pool));
            return types;
        }
    }

    private static String readAnnotationType(DataInputStream input, Object[] pool) throws IOException {
        String type = (String) pool[input.readUnsignedShort()];
        int pairs = input.readUnsignedShort();
        for (int index = 0; index < pairs; index++) {
            input.readUnsignedShort();
            skipElement(input, pool, type);
        }
        return type;
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

    private record ClassMetadata(
        String className,
        String superName,
        List<String> interfaces,
        boolean classAnnotated,
        boolean nested,
        int access,
        List<MethodMetadata> methods,
        boolean runtimeAnnotated
    ) {}

    private record MethodMetadata(String signature, boolean freshJvmAnnotated, boolean junitTestAnnotated, boolean canOverride, boolean runtimeAnnotated) {}
}
