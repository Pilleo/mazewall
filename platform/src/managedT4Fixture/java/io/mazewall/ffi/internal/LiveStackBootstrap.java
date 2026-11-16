package io.mazewall.ffi.internal;

import java.lang.reflect.Executable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Candidate preparation evidence only; startup registration never grants live authority. */
final class LiveStackBootstrap {
    private LiveStackBootstrap() {}

    enum Profile { CLOSED_V1, RETRANSFORM_TEST_V1 }

    enum Variant { ONLY, SHARED }

    record ArtifactIdentity(
            String role,
            String canonicalPath,
            String sha256,
            String buildId,
            String sourceBuildEvidenceId) {
        ArtifactIdentity {
            Objects.requireNonNull(role);
            Objects.requireNonNull(canonicalPath);
            Objects.requireNonNull(sha256);
            Objects.requireNonNull(buildId);
            Objects.requireNonNull(sourceBuildEvidenceId);
        }
    }

    record BindingTarget(int binding, String artifactRole, long entryOffset) {
        BindingTarget {
            if (binding != 1 && binding != 2) throw new IllegalArgumentException("binding outside v1");
            Objects.requireNonNull(artifactRole);
        }
    }

    record DependencyTarget(
            String ownerRole, long resolvedSlotOffset, String targetRole, long targetOffset) {
        DependencyTarget {
            Objects.requireNonNull(ownerRole);
            Objects.requireNonNull(targetRole);
        }
    }

    record RetransformFixture(
            String jarArtifactId,
            String libinstrumentArtifactId,
            String controllerClass,
            String targetClass) {
        RetransformFixture {
            Objects.requireNonNull(jarArtifactId);
            Objects.requireNonNull(libinstrumentArtifactId);
            Objects.requireNonNull(controllerClass);
            Objects.requireNonNull(targetClass);
        }
    }

    record LiveDeploymentIdentity(
            Profile profile,
            Variant variant,
            String sourceEvidenceId,
            List<ArtifactIdentity> artifacts,
            List<String> effectiveVmArguments,
            List<String> orderedClasspath,
            String helperArtifactId,
            List<BindingTarget> bindingTargets,
            List<DependencyTarget> dependencyTargets,
            List<Integer> routeSubset,
            Optional<RetransformFixture> retransformFixture) {
        LiveDeploymentIdentity {
            Objects.requireNonNull(profile);
            Objects.requireNonNull(variant);
            Objects.requireNonNull(sourceEvidenceId);
            artifacts = List.copyOf(artifacts);
            effectiveVmArguments = List.copyOf(effectiveVmArguments);
            orderedClasspath = List.copyOf(orderedClasspath);
            Objects.requireNonNull(helperArtifactId);
            bindingTargets = List.copyOf(bindingTargets);
            dependencyTargets = List.copyOf(dependencyTargets);
            routeSubset = List.copyOf(routeSubset);
            Objects.requireNonNull(retransformFixture);
            for (int route : routeSubset) {
                if (route != 2 && route != 257) throw new IllegalArgumentException("route outside v1");
            }
            if ((profile == Profile.RETRANSFORM_TEST_V1) != retransformFixture.isPresent()) {
                throw new IllegalArgumentException("fixture must match profile");
            }
        }
    }

    static long[] checkDeployment(LiveDeploymentIdentity identity) {
        return checkDeployment0(identity);
    }

    static long[] enroll(long installationId, Class<?>[] owners, Executable[] methods) {
        return enroll0(installationId, owners, methods);
    }

    static void prepareWorker() {
        prepareWorker0();
    }

    static void checkSeal(long installationId, long generation) {
        checkSeal0(installationId, generation);
    }

    private static void checkManagedWitness(Class<?> type, String path) throws java.io.IOException, java.net.URISyntaxException {
        var location = type.getProtectionDomain().getCodeSource();
        if (type.getClassLoader() != ClassLoader.getSystemClassLoader()
                || type.getModule() != ClassLoader.getSystemClassLoader().getUnnamedModule()
                || location == null
                || !java.nio.file.Path.of(location.getLocation().toURI()).toRealPath().toString().equals(path)) {
            throw new UnsupportedOperationException("live deployment: managed witness origin");
        }
    }

    private static void checkFixtureSignature(Class<?> type) throws NoSuchMethodException {
        checkStaticVoid(type.getDeclaredMethod("main", String[].class), "live deployment: managed fixture main");
    }

    private static void checkBridgeSignature(Class<?> type) throws NoSuchMethodException {
        checkStaticVoid(type.getDeclaredMethod("run", String[].class), "live deployment: managed bridge signature");
    }

    private static void checkStaticVoid(java.lang.reflect.Method method, String message) {
        if (method.getReturnType() != void.class || !java.lang.reflect.Modifier.isPublic(method.getModifiers()) || !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
            throw new UnsupportedOperationException(message);
        }
    }

    private static String[] nativeProjection(java.util.Map<String, ArtifactIdentity> artifacts, List<Integer> routes) {
        var result = new java.util.ArrayList<String>();
        for (String role : List.of("agent", "helper", "java", "release", "modules", "libjvm", "libjava", "libnio", "libc", "loader")) {
            result.add(artifacts.get(role).canonicalPath());
            result.add(artifacts.get(role).buildId());
        }
        result.add(Integer.toString((routes.contains(2) ? 1 : 0) | (routes.contains(257) ? 2 : 0)));
        return result.toArray(String[]::new);
    }

    private static String[] nativeProjectionV1(java.util.Map<String, ArtifactIdentity> artifacts, Profile profile,
            int routeMask, boolean zipPresent, boolean simdPresent) {
        if (profile != Profile.CLOSED_V1 || routeMask < 1 || routeMask > 3) throw new UnsupportedOperationException("live projection: profile/routes");
        var roles = new java.util.ArrayList<>(List.of("agent", "java", "libjvm", "libjava", "libnio", "libc", "loader", "libstdc++", "libgcc_s", "libm", "libdl", "libpthread", "librt", "libnet", "libjli", "libjimage", "libmanagement", "libmanagement_ext", "libsyslookup"));
        if (zipPresent) roles.add("libzip");
        if (simdPresent) roles.add("libsimdsort");
        if (profile == Profile.RETRANSFORM_TEST_V1) roles.add("libinstrument");
        var result = new java.util.ArrayList<String>(List.of("mazewall-t4/2", profile == Profile.CLOSED_V1 ? "closed" : "retransform", zipPresent ? "zip-present" : "zip-absent", simdPresent ? "simd-present" : "simd-absent", Integer.toString(routeMask)));
        for (var role : roles) {
            var artifact = artifacts.get(role);
            if (artifact == null || !role.equals(artifact.role()) || !projectionPath(artifact.canonicalPath()) || !projectionBuild(artifact.buildId())) throw new UnsupportedOperationException("live projection: artifact");
            result.add(role); result.add(artifact.canonicalPath()); result.add(artifact.buildId());
        }
        return result.toArray(String[]::new);
    }

    private static boolean projectionPath(String path) {
        if (path.length() > 4096 || !path.startsWith("/") || path.endsWith("/") || path.contains("//") || path.indexOf('\0') >= 0) return false;
        int bytes = 0;
        for (int i = 0; i < path.length(); i++) { char c = path.charAt(i); bytes += c <= 0x7f ? 1 : c <= 0x7ff ? 2 : 3; }
        if (bytes > 4096) return false;
        for (var part : path.split("/", -1)) if (part.equals(".") || part.equals("..")) return false;
        return true;
    }

    private static boolean projectionBuild(String id) {
        return id.length() >= 2 && id.length() <= 128 && id.length() % 2 == 0 && id.matches("[0-9a-f]+");
    }

    private static List<String> managedRoles() {
        return List.of("helper", "enforcer", "kotlin_stdlib", "serialization_core", "serialization_json", "jetbrains_annotations", "managed_fixture");
    }

    private static void checkManagedPrefix(LiveDeploymentIdentity identity, java.util.Map<String, ArtifactIdentity> artifacts, List<String> classpath) {
        var expected = new java.util.HashSet<>(List.of("agent", "helper", "java", "release", "modules", "libjvm", "libjava", "libnio", "libc", "loader", "libstdc++", "libgcc_s", "libm", "libdl", "libpthread", "librt", "libnet", "libjli", "libjimage", "libmanagement", "libmanagement_ext", "libsyslookup"));
        if (artifacts.containsKey("libzip")) expected.add("libzip");
        if (artifacts.containsKey("libsimdsort")) expected.add("libsimdsort");
        expected.addAll(managedRoles());
        if (identity.artifacts().size() != expected.size() || artifacts.size() != expected.size() || !artifacts.keySet().equals(expected)) {
            throw new UnsupportedOperationException("live deployment: managed role set");
        }
        for (var artifact : identity.artifacts()) {
            if (artifacts.get(artifact.role()) != artifact) throw new UnsupportedOperationException("live deployment: duplicate managed role");
        }
        var ordered = managedRoles().stream().map(role -> artifacts.get(role).canonicalPath()).toList();
        if (!identity.orderedClasspath().equals(classpath) || !ordered.equals(classpath)) {
            throw new UnsupportedOperationException("live deployment: managed classpath order");
        }
        for (String path : classpath) {
            if (!path.startsWith("/") || path.endsWith("/") || path.contains("//") || path.indexOf('\0') >= 0) {
                throw new UnsupportedOperationException("live deployment: managed path structure");
            }
            for (String part : path.split("/", -1)) {
                if (part.equals(".") || part.equals("..")) throw new UnsupportedOperationException("live deployment: managed path structure");
            }
        }
    }

    private static void checkManagedLayout(LiveDeploymentIdentity identity, java.util.Map<String, ArtifactIdentity> artifacts, List<String> classpath) {
        checkManagedPrefix(identity, artifacts, classpath);
        try {
            if (!List.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator, -1)).equals(classpath)) {
                throw new UnsupportedOperationException("live deployment: actual managed classpath");
            }
            var files = new java.util.ArrayList<java.nio.file.Path>();
            for (String role : managedRoles()) {
                var artifact = artifacts.get(role);
                if (List.of("helper", "enforcer", "managed_fixture").contains(role)) {
                    if (identity.sourceEvidenceId().isBlank() || !identity.sourceEvidenceId().equals(artifact.sourceBuildEvidenceId())) {
                        throw new UnsupportedOperationException("live deployment: managed source association");
                    }
                } else if (artifact.sourceBuildEvidenceId().isBlank()) {
                    throw new UnsupportedOperationException("live deployment: managed external evidence");
                }
                var path = java.nio.file.Path.of(artifact.canonicalPath());
                if (!path.isAbsolute() || !path.toRealPath().equals(path) || !java.nio.file.Files.isRegularFile(path)) {
                    throw new UnsupportedOperationException("live deployment: managed canonical file");
                }
                for (var previous : files) {
                    if (java.nio.file.Files.isSameFile(previous, path)) throw new UnsupportedOperationException("live deployment: managed file alias");
                }
                files.add(path);
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                try (var input = java.nio.file.Files.newInputStream(path)) {
                    byte[] bytes = new byte[65536];
                    for (int count; (count = input.read(bytes)) != -1;) digest.update(bytes, 0, count);
                }
                var actual = java.util.HexFormat.of().formatHex(digest.digest());
                if (!actual.equals(artifact.sha256()) || !artifact.buildId().equals("sha256:" + actual)) {
                    throw new UnsupportedOperationException("live deployment: managed test digest");
                }
                try (var jar = new java.util.jar.JarFile(path.toFile(), false)) {
                    var manifest = jar.getManifest();
                    if (manifest != null && manifest.getMainAttributes().getValue("Class-Path") != null) {
                        throw new UnsupportedOperationException("live deployment: managed manifest classpath");
                    }
                }
            }
            var witnesses = java.util.Map.of(
                "helper", List.of("io.mazewall.ffi.internal.LiveStackBootstrap", "io.mazewall.ffi.internal.RealNativeStackContexts"),
                "enforcer", List.of("io.mazewall.ffi.internal.OwnedStackScopingBootstrap", "io.mazewall.ffi.internal.OwnedStackScopingBootstrapKt"),
                "kotlin_stdlib", List.of("kotlin.jvm.internal.Intrinsics"),
                "serialization_core", List.of("kotlinx.serialization.KSerializer"),
                "serialization_json", List.of("kotlinx.serialization.json.Json"),
                "jetbrains_annotations", List.of("org.jetbrains.annotations.NotNull"),
                "managed_fixture", List.of("io.mazewall.ffi.internal.OwnedStackScopingPackagedFixture", "io.mazewall.ffi.internal.OwnedStackScopingPackagedBridge", "io.mazewall.ffi.internal.OwnedStackScopingPackagedWorkload")
            );
            for (String role : managedRoles()) {
                for (String name : witnesses.get(role)) {
                    var type = Class.forName(name, false, ClassLoader.getSystemClassLoader());
                    checkManagedWitness(type, artifacts.get(role).canonicalPath());
                    if (List.of("helper", "enforcer", "managed_fixture").contains(role)
                            && !type.getPackageName().equals(LiveStackBootstrap.class.getPackageName())) {
                        throw new UnsupportedOperationException("live deployment: managed runtime package");
                    }
                }
            }
            checkBridgeSignature(Class.forName("io.mazewall.ffi.internal.OwnedStackScopingPackagedBridge", false, ClassLoader.getSystemClassLoader()));
            checkFixtureSignature(Class.forName("io.mazewall.ffi.internal.OwnedStackScopingPackagedFixture", false, ClassLoader.getSystemClassLoader()));
        } catch (java.io.IOException | java.net.URISyntaxException | java.security.NoSuchAlgorithmException | ReflectiveOperationException failure) {
            throw new UnsupportedOperationException("live deployment: managed component inspection", failure);
        }
    }

    // The test harness freezes this manifest before starting the disposable child.
    private static void checkFixtureExpected(LiveDeploymentIdentity identity) throws java.io.IOException {
        var name = System.getProperty("mazewall.fixture.expected");
        if (name == null) throw new UnsupportedOperationException("live deployment: fixture expectations absent");
        var path = java.nio.file.Path.of(name);
        if (!path.isAbsolute() || !path.toRealPath().equals(path) || !java.nio.file.Files.isRegularFile(path)) {
            throw new UnsupportedOperationException("live deployment: fixture expectations path");
        }
        if (java.nio.file.Files.size(path) > 65536) throw new UnsupportedOperationException("live deployment: fixture expectations oversized");
        var expected = new java.util.HashMap<String, String>();
        for (var line : java.nio.file.Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8)) {
            var parts = line.split("\t", -1);
            if (parts.length != 5 || expected.put(parts[0], line) != null) {
                throw new UnsupportedOperationException("live deployment: fixture expectations format");
            }
        }
        if (expected.size() != identity.artifacts().size()) throw new UnsupportedOperationException("live deployment: fixture expectations count");
        for (var artifact : identity.artifacts()) {
            var line = String.join("\t", artifact.role(), artifact.canonicalPath(), artifact.sha256(), artifact.buildId(), artifact.sourceBuildEvidenceId());
            if (!line.equals(expected.get(artifact.role()))) throw new UnsupportedOperationException("live deployment: fixture expectation mismatch");
        }
    }

    // Invoked only by the registered preparation native, before any enrollment gate.
    private static String[] deploymentInputs(LiveDeploymentIdentity identity, int startupVariant) {
        try {
            if (identity == null || identity.profile() != Profile.CLOSED_V1
                    || identity.variant().ordinal() != startupVariant || identity.retransformFixture().isPresent()) {
                throw new UnsupportedOperationException("live deployment: profile/variant or incomplete fixture");
            }
            var fixtureArtifacts = new java.util.HashMap<String, ArtifactIdentity>();
            for (var artifact : identity.artifacts()) {
                if (fixtureArtifacts.put(artifact.role(), artifact) != null) {
                    throw new UnsupportedOperationException("live deployment: duplicate fixture role");
                }
            }
            checkManagedLayout(identity, fixtureArtifacts, identity.orderedClasspath());
            checkFixtureExpected(identity);
            if (!System.getProperty("java.runtime.version").equals("25.0.5+8-LTS")
                    || !System.getProperty("java.vm.name").equals("OpenJDK 64-Bit Server VM")
                    || !System.getProperty("java.vendor").equals("Eclipse Adoptium")
                    || !System.getProperty("os.name").equals("Linux") || !System.getProperty("os.arch").equals("amd64")) {
                throw new UnsupportedOperationException("live deployment: runtime mismatch");
            }
            var actualArguments = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
            if (!actualArguments.equals(identity.effectiveVmArguments())) {
                throw new UnsupportedOperationException("live deployment: effective VM arguments mismatch");
            }
            for (String flag : List.of("-XX:+DisableAttachMechanism", "-XX:-EnableDynamicAgentLoading", "-Xshare:off", "-XX:-EnableJVMCI")) {
                if (actualArguments.stream().filter(flag::equals).count() != 1) {
                    throw new UnsupportedOperationException("live deployment: missing required flag " + flag);
                }
            }
            // Last-option-wins overrides are not admitted merely because a required flag appeared.
            for (String argument : actualArguments) {
                if (List.of("-XX:-DisableAttachMechanism", "-XX:+EnableDynamicAgentLoading", "-XX:+EnableJVMCI", "-Xshare:on", "-Xshare:auto").contains(argument)
                        || argument.startsWith("-javaagent:") || argument.startsWith("-agentlib:")
                        || argument.startsWith("-Xrun") || argument.startsWith("-XX:SharedArchiveFile=")
                        || argument.startsWith("-Djava.system.class.loader=") || argument.startsWith("--patch-module")
                        || argument.startsWith("--module-path") || argument.startsWith("-p=") || argument.startsWith("-Xbootclasspath")) {
                    throw new UnsupportedOperationException("live deployment: incompatible launch participant/override");
                }
            }
            var classpath = List.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator, -1));
            if (!classpath.equals(identity.orderedClasspath()) || classpath.isEmpty()
                    || LiveStackBootstrap.class.getClassLoader() != ClassLoader.getSystemClassLoader()
                    || LiveStackBootstrap.class.getModule() != ClassLoader.getSystemClassLoader().getUnnamedModule()) {
                throw new UnsupportedOperationException("live deployment: classpath/helper loader/module mismatch");
            }
            if (!identity.sourceEvidenceId().matches("sha256:[0-9a-f]{64}") || !identity.helperArtifactId().equals("helper")) {
                throw new UnsupportedOperationException("live deployment: missing source/build evidence identity");
            }
            var roles = identity.artifacts().stream().map(ArtifactIdentity::role).toList();
            var fixedHashes = java.util.Map.of(
                "java", "1bf1aaef01e4f8d7c85d913c60fb2ee4624cbe54d4053254c59657b7ea913b25",
                "release", "8545053a54a73969a65db2c8157f40f89bcdcf7526ae90a7ce9b3061c01bc34a",
                "modules", "f5463cffa81c51b3c13f57e2c23eacceab2b12e842069acf2facd5485f63bc85",
                "libjvm", "30b96b2f2b69f313f4c85d9b437511a1d97e64c29a3d13b0abd6b07c7f50335b",
                "libjava", "af54a944d819ebfdec651a9b6b77dc7a4126c871f6b74dcf1fd69aa8edd17053",
                "libnio", "1db89f7ef9251543c9bf46c6355046d8ba8b6620ed908c7f9487e52db3ecee08",
                "libc", "85e64f97e348786a8fb4d9f3d52fec289e2fb86bba20f0731dfe61990525e0f7",
                "loader", "bda779ea9e9ad234f60315477d7cfd0bdde861cd8ddd9df8cdaf62f18c4cec14");
            if (identity.artifacts().size() != roles.size()) throw new UnsupportedOperationException("live deployment: artifact set");
            var artifacts = new java.util.HashMap<String, ArtifactIdentity>();
            for (var artifact : identity.artifacts()) {
                if (!roles.contains(artifact.role()) || artifacts.put(artifact.role(), artifact) != null) {
                    throw new UnsupportedOperationException("live deployment: duplicate/unknown artifact");
                }
                var path = java.nio.file.Path.of(artifact.canonicalPath());
                if (!path.isAbsolute() || !path.toRealPath().equals(path) || !java.nio.file.Files.isRegularFile(path)) {
                    throw new UnsupportedOperationException("live deployment: noncanonical artifact");
                }
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                try (var input = java.nio.file.Files.newInputStream(path)) {
                    byte[] bytes = new byte[65536];
                    for (int count; (count = input.read(bytes)) != -1;) digest.update(bytes, 0, count);
                }
                String actual = java.util.HexFormat.of().formatHex(digest.digest());
                if (!actual.equals(artifact.sha256()) || !actual.equals(fixedHashes.getOrDefault(artifact.role(), actual))) {
                    throw new UnsupportedOperationException("live deployment: artifact digest mismatch " + artifact.role());
                }
                if (artifact.sourceBuildEvidenceId().isBlank()) throw new UnsupportedOperationException("live deployment: source association");
                if (List.of("helper", "release", "modules").contains(artifact.role()) && !artifact.buildId().equals("sha256:" + actual)) {
                    throw new UnsupportedOperationException("live deployment: non-ELF build identity");
                }
            }
            var home = java.nio.file.Path.of(System.getProperty("java.home"));
            var relative = java.util.Map.of("java", "bin/java", "release", "release", "modules", "lib/modules",
                "libjvm", "lib/server/libjvm.so", "libjava", "lib/libjava.so", "libnio", "lib/libnio.so", "libsyslookup", "lib/libsyslookup.so");
            for (var entry : relative.entrySet()) {
                if (!home.resolve(entry.getValue()).toRealPath().toString().equals(artifacts.get(entry.getKey()).canonicalPath())) {
                    throw new UnsupportedOperationException("live deployment: runtime artifact path");
                }
            }
            String helperPath = java.nio.file.Path.of(LiveStackBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().toString();
            if (!helperPath.equals(artifacts.get("helper").canonicalPath()) || !classpath.contains(helperPath)) {
                throw new UnsupportedOperationException("live deployment: actual helper artifact");
            }
            for (String entry : classpath) {
                if (!java.nio.file.Path.of(entry).toRealPath().toString().equals(entry)
                        || !artifacts.values().stream().anyMatch(a -> a.canonicalPath().equals(entry))) {
                    throw new UnsupportedOperationException("live deployment: unmeasured classpath participant");
                }
            }
            String expectedAgent = "-agentpath:" + artifacts.get("agent").canonicalPath() + "=live-stack="
                + (identity.variant() == Variant.ONLY ? "only" : "shared");
            if (actualArguments.stream().filter(a -> a.startsWith("-agentpath:")).count() != 1 || !actualArguments.contains(expectedAgent)) {
                throw new UnsupportedOperationException("live deployment: extra/renamed/substituted agent");
            }
            if (!identity.bindingTargets().equals(List.of(new BindingTarget(1, "libnio", 0xe950), new BindingTarget(2, "libnio", 0xe9e0)))
                    || !identity.dependencyTargets().equals(List.of(
                        new DependencyTarget("libnio", 0x15ee0, "libc", 0x127990),
                        new DependencyTarget("libnio", 0x16100, "libc", 0x127a90),
                        new DependencyTarget("libnio", 0x15c18, "libc", 0x2a980),
                        new DependencyTarget("libnio", 0x15f40, "libjava", 0x1a0a0),
                        new DependencyTarget("libjvm", 0x178cad0, "libjvm", 0xb03f90),
                        new DependencyTarget("libjvm", 0x178cb08, "libjvm", 0xb01220),
                        new DependencyTarget("libjvm", 0x178cb58, "libjvm", 0xaf7300),
                        new DependencyTarget("libjvm", 0x178cb70, "libjvm", 0xaf6980),
                        new DependencyTarget("libjvm", 0x178cb88, "libjvm", 0xb0f040),
                        new DependencyTarget("libjvm", 0x178cba8, "libjvm", 0xb05e90)))
                    || identity.routeSubset().isEmpty() || identity.routeSubset().stream().distinct().count() != identity.routeSubset().size()) {
                throw new UnsupportedOperationException("live deployment: unaudited binding/dependency/route");
            }
            int routeMask = (identity.routeSubset().contains(2) ? 1 : 0) | (identity.routeSubset().contains(257) ? 2 : 0);
            return nativeProjectionV1(artifacts, identity.profile(), routeMask, artifacts.containsKey("libzip"), artifacts.containsKey("libsimdsort"));
        } catch (java.io.IOException | java.net.URISyntaxException | java.security.NoSuchAlgorithmException failure) {
            throw new UnsupportedOperationException("live deployment: artifact inspection failed", failure);
        }
    }

    private static native boolean probe0();

    private static native long[] checkDeployment0(LiveDeploymentIdentity identity);

    private static native long[] enroll0(long installationId, Class<?>[] owners, Executable[] methods);

    private static native void prepareWorker0();

    private static native void checkSeal0(long installationId, long generation);
}
