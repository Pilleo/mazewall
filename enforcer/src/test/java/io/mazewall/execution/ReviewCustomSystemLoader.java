package io.mazewall.execution;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.SecureClassLoader;
import java.security.cert.Certificate;
import java.util.jar.JarFile;

/** Defines counterfeit bytes but reports the original JAR/resources as its source. */
public final class ReviewCustomSystemLoader extends SecureClassLoader {
    public ReviewCustomSystemLoader(ClassLoader parent) { super(parent); }

    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!name.equals("fixture.Operation")) return super.loadClass(name, resolve);
        synchronized (getClassLoadingLock(name)) {
            Class<?> type = findLoadedClass(name);
            if (type == null) {
                try {
                    byte[] bytes = Files.readAllBytes(Path.of(System.getProperty("task5.counterfeit.bytes")));
                    URL source = Path.of(System.getProperty("task5.original.jar")).toUri().toURL();
                    type = defineClass(name, bytes, 0, bytes.length, new CodeSource(source, (Certificate[]) null));
                } catch (Exception failure) { throw new ClassNotFoundException(name, failure); }
            }
            if (resolve) resolveClass(type);
            return type;
        }
    }

    @Override public URL getResource(String name) {
        if (!name.equals("fixture/Operation.class")) return super.getResource(name);
        try { return new URL("jar:" + Path.of(System.getProperty("task5.original.jar")).toUri() + "!/" + name); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    @Override public InputStream getResourceAsStream(String name) {
        if (!name.equals("fixture/Operation.class")) return super.getResourceAsStream(name);
        try (JarFile jar = new JarFile(System.getProperty("task5.original.jar"))) {
            try (InputStream input = jar.getInputStream(jar.getJarEntry(name))) {
                return new ByteArrayInputStream(input.readAllBytes());
            }
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
