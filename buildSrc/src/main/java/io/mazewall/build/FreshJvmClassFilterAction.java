package io.mazewall.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.tasks.testing.Test;

/**
 * Limits a fresh-JVM test task to classes containing the {@code NeedsFreshJvm}
 * annotation. A compiled action avoids capturing a Kotlin build-script object,
 * which Gradle's configuration cache cannot serialize.
 */
public final class FreshJvmClassFilterAction implements Action<Task> {
    private static final String FRESH_JVM_MARKER = "NeedsFreshJvm";
    private static final String ANNOTATION_CLASS_SUFFIX = ".NeedsFreshJvm";

    @Override
    public void execute(Task task) {
        Test test = (Test) task;
        test.getFilter().setFailOnNoMatchingTests(false);
        for (String className : freshJvmClassNames(test)) {
            test.getFilter().includeTestsMatching(className);
        }
    }

    private static List<String> freshJvmClassNames(Test test) {
        List<String> classNames = new ArrayList<>();
        for (File root : test.getTestClassesDirs().getFiles()) {
            if (!root.isDirectory()) {
                continue;
            }
            collectFreshJvmClassNames(root, root, classNames);
        }
        return classNames;
    }

    private static void collectFreshJvmClassNames(File root, File current, List<String> classNames) {
        File[] children = current.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectFreshJvmClassNames(root, child, classNames);
            } else if (child.getName().endsWith(".class") && !child.getName().contains("$")) {
                addIfFreshJvmClass(root, child, classNames);
            }
        }
    }

    private static void addIfFreshJvmClass(File root, File classFile, List<String> classNames) {
        try {
            String bytes = Files.readString(classFile.toPath(), StandardCharsets.ISO_8859_1);
            if (!bytes.contains(FRESH_JVM_MARKER)) {
                return;
            }
            String relativePath = root.toPath().relativize(classFile.toPath()).toString();
            String className = relativePath.substring(0, relativePath.length() - ".class".length())
                .replace(File.separatorChar, '.');
            if (!className.endsWith(ANNOTATION_CLASS_SUFFIX)) {
                classNames.add(className);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot inspect fresh-JVM test class " + classFile, e);
        }
    }
}
