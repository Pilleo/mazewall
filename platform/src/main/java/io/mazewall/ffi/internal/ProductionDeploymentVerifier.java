package io.mazewall.ffi.internal;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarFile;

/** Observes the running JVM; the native startup snapshot remains the authority. */
final class ProductionDeploymentVerifier {
    private ProductionDeploymentVerifier() {}

    static String[] observe(LiveStackBootstrap.LiveDeploymentIdentity identity) {
        try {
            return observeChecked(identity);
        } catch (Exception failure) {
            throw new UnsupportedOperationException("live deployment: managed observation failed", failure);
        }
    }

    private static String[] observeChecked(LiveStackBootstrap.LiveDeploymentIdentity identity) throws Exception {
        var runtime = List.of(System.getProperty("java.runtime.version"), System.getProperty("java.vm.name"),
                System.getProperty("java.vendor"), System.getProperty("os.name"), System.getProperty("os.arch"));
        if (!runtime.equals(List.of("25.0.5+8-LTS", "OpenJDK 64-Bit Server VM", "Eclipse Adoptium", "Linux", "amd64"))) {
            throw new UnsupportedOperationException("live deployment: runtime mismatch");
        }
        var arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        if (arguments.size() > 128) throw new UnsupportedOperationException("live deployment: too many arguments");
        for (String flag : List.of("-XX:+DisableAttachMechanism", "-XX:-EnableDynamicAgentLoading", "-Xshare:off", "-XX:-EnableJVMCI")) {
            if (arguments.stream().filter(flag::equals).count() != 1) throw new UnsupportedOperationException("live deployment: required flag");
        }
        for (String arg : arguments) {
            if (List.of("-XX:-DisableAttachMechanism", "-XX:+EnableDynamicAgentLoading", "-XX:+EnableJVMCI", "-Xshare:on", "-Xshare:auto").contains(arg)
                    || arg.startsWith("-javaagent:") || arg.startsWith("-agentlib:") || arg.startsWith("-Xrun")
                    || arg.startsWith("-XX:SharedArchiveFile=") || arg.startsWith("-Djava.system.class.loader=")
                    || arg.startsWith("--patch-module") || arg.startsWith("--module-path") || arg.startsWith("-p=")
                    || arg.startsWith("-Xbootclasspath")) throw new UnsupportedOperationException("live deployment: launch participant");
        }
        if (arguments.stream().filter(arg -> arg.startsWith("-agentpath:")).count() != 1
                || arguments.stream().noneMatch(arg -> arg.startsWith("-agentpath:") && arg.endsWith("=live-stack=only"))) {
            throw new UnsupportedOperationException("live deployment: agent selection");
        }
        if (!identity.routeSubset().equals(List.of(2, 257))) throw new UnsupportedOperationException("live deployment: requested routes");
        var agents = identity.artifacts().stream().filter(artifact -> artifact.role().equals("agent")).toList();
        if (agents.size() != 1) throw new UnsupportedOperationException("live deployment: requested agent");
        String agentArgument = arguments.stream().filter(arg -> arg.startsWith("-agentpath:")).findFirst().orElseThrow();
        String actualAgent = agentArgument.substring("-agentpath:".length(), agentArgument.length() - "=live-stack=only".length());
        var agentPath = Path.of(actualAgent);
        if (!agentPath.isAbsolute() || !agentPath.toRealPath().equals(agentPath)
                || !agents.get(0).canonicalPath().equals(actualAgent))
            throw new UnsupportedOperationException("live deployment: requested agent path");
        var classpath = List.of(System.getProperty("java.class.path").split(File.pathSeparator, -1));
        if (classpath.size() != 7) throw new UnsupportedOperationException("live deployment: classpath count");
        var paths = new ArrayList<Path>();
        var hashes = new ArrayList<String>();
        var names = new HashSet<String>();
        for (String entry : classpath) {
            var path = Path.of(entry);
            if (!path.isAbsolute() || !path.toRealPath().equals(path) || !Files.isRegularFile(path))
                throw new UnsupportedOperationException("live deployment: classpath path");
            for (Path previous : paths) if (Files.isSameFile(previous, path))
                throw new UnsupportedOperationException("live deployment: classpath alias");
            paths.add(path);
            if (Files.size(path) > (1L << 30)) throw new UnsupportedOperationException("live deployment: artifact too large");
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                long total = 0;
                for (int n; (n = input.read(buffer)) != -1;) {
                    if (n > (1L << 30) - total) throw new UnsupportedOperationException("live deployment: artifact too large");
                    total += n;
                    digest.update(buffer, 0, n);
                }
            }
            hashes.add(HexFormat.of().formatHex(digest.digest()));
            try (var jar = new JarFile(path.toFile(), false, JarFile.OPEN_READ, Runtime.version())) {
                var manifest = jar.getManifest();
                if (manifest != null && manifest.getMainAttributes().getValue("Class-Path") != null)
                    throw new UnsupportedOperationException("live deployment: manifest classpath");
                for (var iterator = jar.versionedStream().iterator(); iterator.hasNext();) {
                    var entryName = iterator.next().getName();
                    if (entryName.endsWith(".class") && !entryName.equals("module-info.class") && !names.add(entryName))
                        throw new UnsupportedOperationException("live deployment: duplicate class");
                }
            }
        }
        var witnesses = List.of(
                "io.mazewall.ffi.internal.LiveStackBootstrap", "io.mazewall.ffi.internal.ProductionDeploymentVerifier",
                "io.mazewall.ffi.internal.RealNativeStackContexts",
                "io.mazewall.ffi.internal.OwnedStackScopingBootstrap", "io.mazewall.ffi.internal.OwnedStackScopingBootstrapKt",
                "kotlin.jvm.internal.Intrinsics", "kotlinx.serialization.KSerializer", "kotlinx.serialization.json.Json",
                "org.jetbrains.annotations.NotNull", "io.mazewall.ffi.internal.OwnedStackScopingPackagedFixture",
                "io.mazewall.ffi.internal.OwnedStackScopingPackagedBridge", "io.mazewall.ffi.internal.OwnedStackScopingPackagedWorkload");
        var expectedIndex = List.of(0, 0, 0, 1, 1, 2, 3, 4, 5, 6, 6, 6);
        var projection = new ArrayList<String>(List.of("mazewall-observed-java/1", "CLOSED_V1", "ONLY"));
        projection.addAll(runtime);
        var javaHome = Path.of(System.getProperty("java.home"));
        if (!javaHome.isAbsolute() || !javaHome.toRealPath().equals(javaHome))
            throw new UnsupportedOperationException("live deployment: Java home");
        projection.add(javaHome.toString());
        projection.add(Integer.toString(arguments.size()));
        for (int i = 0; i < arguments.size(); i++) { projection.add(Integer.toString(i)); projection.add(arguments.get(i)); }
        projection.add(Integer.toString(paths.size()));
        for (int i = 0; i < paths.size(); i++) { projection.add(Integer.toString(i)); projection.add(paths.get(i).toString()); projection.add(hashes.get(i)); }
        projection.add(Integer.toString(witnesses.size()));
        for (int i = 0; i < witnesses.size(); i++) {
            var type = Class.forName(witnesses.get(i), false, ClassLoader.getSystemClassLoader());
            var source = type.getProtectionDomain().getCodeSource();
            if (type.getClassLoader() != ClassLoader.getSystemClassLoader()
                    || type.getModule() != ClassLoader.getSystemClassLoader().getUnnamedModule() || source == null)
                throw new UnsupportedOperationException("live deployment: witness loader/module");
            var actualPath = Path.of(source.getLocation().toURI()).toRealPath();
            if (!actualPath.equals(paths.get(expectedIndex.get(i)))) throw new UnsupportedOperationException("live deployment: witness source");
            projection.add(Integer.toString(i)); projection.add(type.getName()); projection.add("system");
            projection.add("unnamed"); projection.add(actualPath.toString());
        }
        int total = 0;
        for (String field : projection) {
            if (field.indexOf('\0') >= 0) throw new UnsupportedOperationException("live deployment: NUL field");
            var encoder = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            int bytes = encoder.encode(java.nio.CharBuffer.wrap(field)).remaining();
            if (bytes > 4096 || total > 262144 - bytes) throw new UnsupportedOperationException("live deployment: projection size");
            total += bytes;
        }
        if (projection.size() > 2048) throw new UnsupportedOperationException("live deployment: projection count");
        return projection.toArray(String[]::new);
    }
}
