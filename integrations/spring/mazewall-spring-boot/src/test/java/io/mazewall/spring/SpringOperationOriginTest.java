package io.mazewall.spring;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import org.junit.jupiter.api.Test;

class SpringOperationOriginTest {
    static class Selected {}
    static class Declaring {}

    @Test void sameOriginDoesNotInvokeUrlEqualityHandlers() throws Exception {
        var handler = new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) { throw new AssertionError("Origin comparison opened a connection"); }
            @Override protected boolean equals(URL first, URL second) { throw new AssertionError("Origin comparison invoked URL equality"); }
        };
        var loader = new OriginLoader();
        Class<?> selected = loader.define(Selected.class, new URL(null, "file:/fixture/ordinary.jar", handler));
        Class<?> declaring = loader.define(Declaring.class, new URL(null, "file:/fixture/ordinary.jar", handler));

        assertTrue(assertDoesNotThrow(() -> sameOrigin(selected, declaring)));
    }

    @Test void equivalentLocalFileUrisMatchButDifferentJarsOrLoadersDoNot() throws Exception {
        var loader = new OriginLoader();
        var selected = loader.define(Selected.class, new URL("file:/fixture/ordinary.jar"));
        var declaring = loader.define(Declaring.class, new URL("file:///fixture/ordinary.jar"));
        assertTrue(sameOrigin(selected, declaring));
        var other = new OriginLoader();
        assertFalse(sameOrigin(selected, other.define(Declaring.class, new URL("file:/fixture/ordinary.jar"))));
        var different = new OriginLoader();
        assertFalse(sameOrigin(different.define(Selected.class, new URL("file:/fixture/ordinary.jar")),
                different.define(Declaring.class, new URL("file:/fixture/other.jar"))));
    }

    @Test void hostAliasesAndMalformedOrMissingLocationsAreNotApproval() throws Exception {
        for (String location : new String[] {"file://localhost/fixture/ordinary.jar", "file:/fixture/bad path.jar", null}) {
            var loader = new OriginLoader();
            var selected = loader.define(Selected.class, new URL("file:/fixture/ordinary.jar"));
            var declaring = loader.define(Declaring.class, location == null ? null : new URL(location));
            assertFalse(assertDoesNotThrow(() -> sameOrigin(selected, declaring)));
        }
    }

    private static boolean sameOrigin(Class<?> selected, Class<?> declaring) throws ReflectiveOperationException {
        // Exercise the private decision without adding a production test-only entrypoint.
        var method = SpringOperationBinder.class.getDeclaredMethod("sameOrdinaryJarOrigin", Class.class, Class.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, selected, declaring);
    }

    private static final class OriginLoader extends ClassLoader {
        OriginLoader() { super(SpringOperationOriginTest.class.getClassLoader()); }

        Class<?> define(Class<?> template, URL location) throws IOException {
            try (var input = template.getResourceAsStream("/" + template.getName().replace('.', '/') + ".class")) {
                assertNotNull(input);
                byte[] bytes = input.readAllBytes();
                var source = new CodeSource(location, (Certificate[]) null);
                return defineClass(template.getName(), bytes, 0, bytes.length, new ProtectionDomain(source, null, this, null));
            }
        }
    }
}
