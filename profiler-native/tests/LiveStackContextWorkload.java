package io.mazewall.ffi.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;

/** Package-matched startup subprocess checks, not target-chain qualification. */
public final class LiveStackContextWorkload {
    private LiveStackContextWorkload() {}

    public static final class DelegatingSystemLoader extends ClassLoader {
        public DelegatingSystemLoader(ClassLoader parent) { super(parent); }
    }

    public static void main(String[] args) throws Throwable {
        if (args[0].equals("deployment_projection_dispatch")) {
            var input = new java.util.ArrayList<String>(List.of("mazewall-t4/1", "closed", "zip-absent", "simd-absent", "1"));
            for (String role : List.of("agent", "java", "libjvm", "libjava", "libnio", "libc", "loader", "libstdc++", "libgcc_s", "libm", "libdl", "libpthread", "librt", "libnet", "libjli", "libjimage", "libmanagement", "libmanagement_ext")) {
                input.add(role); input.add("/native/" + role); input.add("ab");
            }
            String[] legacy = new String[21];
            java.util.Arrays.fill(legacy, "legacy");
            require(control0(41, 0, legacy) == 0, "legacy 21 remains legacy");
            legacy[0] = "mazewall-observed-java/1";
            require(control0(41, 0, legacy) == 2, "protected magic cannot enter legacy dispatch");
            long mappings = control0(42, 0, null), initialized = control0(2, 0, null), refs = control0(21, 0, null);
            for (int branch = 0; branch < 4; ++branch) {
                var v2 = new ArrayList<String>(List.of("mazewall-t4/2", "closed", (branch & 1) != 0 ? "zip-present" : "zip-absent", (branch & 2) != 0 ? "simd-present" : "simd-absent", "1"));
                var roles = new ArrayList<String>(List.of("agent", "java", "libjvm", "libjava", "libnio", "libc", "loader", "libstdc++", "libgcc_s", "libm", "libdl", "libpthread", "librt", "libnet", "libjli", "libjimage", "libmanagement", "libmanagement_ext", "libsyslookup"));
                if ((branch & 1) != 0) roles.add("libzip");
                if ((branch & 2) != 0) roles.add("libsimdsort");
                for (String role : roles) { v2.add(role); v2.add("/native/" + role); v2.add("ab"); }
                String[] candidate = v2.toArray(String[]::new);
                require(control0(41, 0, candidate) == 1, "valid v2 extended representation refused");
                candidate[0] = "mazewall-t4/3";
                require(control0(41, 0, candidate) == 2, "unknown projection version refused");
                candidate[0] = "mazewall-t4/1";
                require(control0(41, 0, candidate) == 2, "v2 roles cannot enter v1 parser");
                candidate[0] = "mazewall-t4/2"; candidate[1] = "retransform";
                require(control0(41, 0, candidate) == 2, "v2 retransform refused");
                candidate[1] = "closed"; candidate[5 + 3 * 18] = "libzip";
                require(control0(41, 0, candidate) == 2, "malformed v2 role refused");
                require(control0(42, 0, null) == mappings && control0(2, 0, null) == initialized && control0(21, 0, null) == refs, "v2 dispatch causes no effects");
            }
            String[] array = input.toArray(String[]::new);
            require(control0(41, 0, array) == 1, "valid extended representation refused");
            array[5] = "wrong";
            require(control0(41, 0, array) == 2, "malformed representation refused");
            array[5] = "agent"; array[6] = "/" + "a".repeat(4096);
            require(control0(41, 0, array) == 2, "overlong path refused before copy");
            array[6] = "/native/agent"; array[7] = "a".repeat(130);
            require(control0(41, 0, array) == 2, "overlong build ID refused");
            array[7] = "ab"; array[6] = "/bad/\0path";
            require(control0(41, 0, array) == 2, "modified UTF-8 NUL refused");
            require(control0(41, 0, new String[69]) == 2, "oversized array refused before copying");
            var exact = new OutOfMemoryError("projection pending"); control0(7, 0, exact);
            try { control0(41, 1, array); throw new AssertionError("pending throwable lost"); }
            catch (OutOfMemoryError actual) { require(actual == exact, "pending throwable identity"); }
            control0(7, 0, null);
            require(control0(42, 0, null) == mappings && control0(2, 0, null) == initialized && control0(21, 0, null) == refs, "dispatch causes no effects");
            return;
        }
        if (args[0].equals("deployment_launch_matrix")) { launchMatrix(args); return; }
        if (args[0].startsWith("deployment") || args[0].startsWith("enrollment") || args[0].startsWith("guard_") || args[0].equals("host_runtime_refusal")) {
            preparation(args);
            return;
        }
        if (args[0].equals("child")) {
            // No body marker may appear when required VMInit setup fails.
            System.out.println("live-stack-workload: admitted");
            checkHelper();
            try (var arena = Arena.ofConfined()) {
                var lookup = SymbolLookup.libraryLookup(Path.of(args[1]), arena);
                var info = arena.allocate(64, 8);
                info.fill((byte) 0x7f);
                var call = Linker.nativeLinker().downcallHandle(
                    lookup.find("mazewall_live_info_v1").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                int status = (int) call.invokeExact(info, 64);
                require(status == 5 && info.get(ValueLayout.JAVA_INT, 8) == 5, "core must stay Unsupported");
                for (long i = 16; i < 64; i++) require(info.get(ValueLayout.JAVA_BYTE, i) == 0, "unready payload");
            }
            System.out.println("live-stack-workload: helper registered; core Unsupported");
            return;
        }
        String test = args[0];
        Path agent = Path.of(args[1]);
        Path classes = Path.of(args[2]);
        Path output = Files.createTempDirectory(Files.createDirectories(Path.of(args[3])), "attempt-");
        Path childClasses = classes;
        String expected = null;
        String mode = test.equals("shared") || test.endsWith("_shared") ? "shared" : "only";
        if (test.startsWith("probe_true")) expected = "probe preparation must return false";
        if (test.startsWith("probe_throw")) expected = "probe preparation exception";
        if (test.equals("missing") || test.equals("mismatch") || test.equals("annotated")) {
            childClasses = Files.createDirectory(output.resolve("classes"));
            Path pkg = Files.createDirectories(childClasses.resolve("io/mazewall/ffi/internal"));
            Path sourcePkg = classes.resolve("io/mazewall/ffi/internal");
            try (var files = Files.list(sourcePkg)) {
                for (var file : files.filter(p -> p.getFileName().toString().startsWith("LiveStackContextWorkload")).toList()) {
                    Files.copy(file, pkg.resolve(file.getFileName()));
                }
            }
            expected = test.equals("missing") ? "missing helper" : "registration signature mismatch";
            if (!test.equals("missing")) {
                Path source = output.resolve("LiveStackBootstrap.java");
                String body = "private static native boolean probe0();";
                if (test.equals("annotated")) {
                    expected = "probe must be nonintrinsic and unannotated";
                    body = "@Deprecated private static native boolean probe0(); static final class LiveDeploymentIdentity {} "
                        + "private static native long[] checkDeployment0(LiveDeploymentIdentity id); "
                        + "private static native long[] enroll0(long id, Class<?>[] owners, java.lang.reflect.Executable[] methods); "
                        + "private static native void prepareWorker0(); private static native void checkSeal0(long id, long generation);";
                }
                Files.writeString(source, "package io.mazewall.ffi.internal; final class LiveStackBootstrap { " + body + " }");
                int result = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "--release", "22", "-d", childClasses.toString(), source.toString());
                require(result == 0, "negative helper javac");
            }
        }
        List<String> command = new ArrayList<>(List.of("/bin/sh", "-c", "ulimit -c 0; exec \"$@\"", "live-startup",
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-XX:-CreateCoredumpOnCrash", "-XX:ErrorFile=" + output.resolve("hs_err_pid%p.log"),
            "--enable-native-access=ALL-UNNAMED", "-agentpath:" + agent + "=live-stack=" + mode));
        if (test.equals("loader")) {
            command.add("-Djava.system.class.loader=" + DelegatingSystemLoader.class.getName());
            expected = "wrong defining loader";
        }
        command.addAll(List.of("-cp", childClasses.toString(), LiveStackContextWorkload.class.getName(), "child", agent.toString()));
        Files.writeString(output.resolve("command.txt"), command.toString());
        Path log = output.resolve("child.log");
        Process process = new ProcessBuilder(command).directory(output.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean exited = process.waitFor(Duration.ofSeconds(25).toMillis(), TimeUnit.MILLISECONDS);
        if (!exited) { process.destroyForcibly(); process.waitFor(); }
        require(exited, "startup deadline");
        Files.writeString(output.resolve("exit.txt"), Integer.toString(process.exitValue()));
        String text = Files.readString(log);
        if (expected == null) {
            require(process.exitValue() == 0 && text.contains("helper registered; core Unsupported"), text);
        } else {
            require(process.exitValue() != 0 && text.contains(expected), "expected cause " + expected + ": " + text);
            require(!text.contains("live-stack-workload: admitted"), "workload admitted after failure");
        }
        System.out.println("live-stack-startup: " + test + " passed (host only)");
    }

    private static void checkHelper() throws Throwable {
        Class<?> helper = Class.forName("io.mazewall.ffi.internal.LiveStackBootstrap");
        require(helper.getClassLoader() == ClassLoader.getSystemClassLoader(), "actual system helper loader");
        require(helper.getModule() == ClassLoader.getSystemClassLoader().getUnnamedModule(), "actual unnamed module");
        require(Modifier.isFinal(helper.getModifiers()) && !Modifier.isPublic(helper.getModifiers()), "hidden final helper");
        int natives = 0;
        for (var method : helper.getDeclaredMethods()) {
            if (!Modifier.isNative(method.getModifiers())) continue;
            natives++;
            require(method.getModifiers() == (Modifier.PRIVATE | Modifier.STATIC | Modifier.NATIVE), "ordinary private static native");
            require(method.getDeclaredAnnotations().length == 0, "no intrinsic annotation");
            method.setAccessible(true);
            if (method.getName().equals("probe0")) {
                require(Boolean.FALSE.equals(method.invoke(null)), "T1 probe must refuse");
            } else {
                Object[] values = new Object[method.getParameterCount()];
                for (int i = 0; i < values.length; i++) if (method.getParameterTypes()[i] == long.class) values[i] = 0L;
                try { method.invoke(null, values); throw new AssertionError("setup accepted"); }
                catch (InvocationTargetException e) { require(e.getCause() instanceof UnsupportedOperationException, "explicit Unsupported: " + e); }
            }
        }
        require(natives == 5, "exact five registered natives");
        checkIdentityValues();
    }

    private static void checkIdentityValues() {
        var artifact = new LiveStackBootstrap.ArtifactIdentity("agent", "/candidate", "digest", "build", "source");
        var artifacts = new ArrayList<>(List.of(artifact));
        var arguments = new ArrayList<>(List.of("-Xshare:off"));
        var classpath = new ArrayList<>(List.of("/candidate"));
        var bindings = new ArrayList<>(List.of(new LiveStackBootstrap.BindingTarget(1, "agent", 1L)));
        var dependencies = new ArrayList<>(List.of(new LiveStackBootstrap.DependencyTarget("agent", 2L, "libc", 3L)));
        var routes = new ArrayList<>(List.of(2, 257));
        var identity = new LiveStackBootstrap.LiveDeploymentIdentity(
            LiveStackBootstrap.Profile.CLOSED_V1, LiveStackBootstrap.Variant.ONLY, "source", artifacts,
            arguments, classpath, "helper", bindings, dependencies, routes, Optional.empty());
        artifacts.clear(); arguments.clear(); classpath.clear(); bindings.clear(); dependencies.clear(); routes.clear();
        require(identity.artifacts().size() == 1 && identity.effectiveVmArguments().size() == 1 &&
            identity.orderedClasspath().size() == 1 && identity.bindingTargets().size() == 1 &&
            identity.dependencyTargets().size() == 1 && identity.routeSubset().equals(List.of(2, 257)), "defensive copies");
        try { identity.routeSubset().add(437); throw new AssertionError("mutable routes"); }
        catch (UnsupportedOperationException expected) { /* immutable value */ }
        try { new LiveStackBootstrap.BindingTarget(3, "agent", 0); throw new AssertionError("invalid binding"); }
        catch (IllegalArgumentException expected) { /* closed bindings */ }
        for (var profile : LiveStackBootstrap.Profile.values()) {
            var fixture = new LiveStackBootstrap.RetransformFixture("jar", "instrument", "controller", "target");
            for (boolean present : List.of(false, true)) {
                boolean valid = (profile == LiveStackBootstrap.Profile.RETRANSFORM_TEST_V1) == present;
                try {
                    new LiveStackBootstrap.LiveDeploymentIdentity(profile, LiveStackBootstrap.Variant.SHARED,
                        "source", List.of(), List.of(), List.of(), "helper", List.of(), List.of(), List.of(257),
                        present ? Optional.of(fixture) : Optional.empty());
                    require(valid, "invalid fixture accepted");
                } catch (IllegalArgumentException e) { require(!valid, "valid fixture rejected"); }
            }
        }
        try {
            new LiveStackBootstrap.LiveDeploymentIdentity(LiveStackBootstrap.Profile.CLOSED_V1,
                LiveStackBootstrap.Variant.ONLY, "source", List.of(), List.of(), List.of(), "helper",
                List.of(), List.of(), List.of(437), Optional.empty());
            throw new AssertionError("invalid route");
        } catch (IllegalArgumentException expected) { /* closed routes */ }
        try { LiveStackBootstrap.checkDeployment(identity); throw new AssertionError("candidate approved"); }
        catch (UnsupportedOperationException expected) { /* candidate data is not approval */ }
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static String digest(Path path) throws Exception {
        var md = java.security.MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(path)) {
            byte[] bytes = new byte[65536];
            for (int n; (n = in.read(bytes)) != -1;) md.update(bytes, 0, n);
        }
        return java.util.HexFormat.of().formatHex(md.digest());
    }

    private static String buildId(Path path, Path output, String role) throws Exception {
        Path log = output.resolve("readelf-" + role + ".log");
        var command = List.of("/usr/bin/readelf", "-n", path.toString());
        Files.writeString(output.resolve("readelf-" + role + ".command"), command.toString(), java.nio.file.StandardOpenOption.CREATE_NEW);
        var child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        require(child.waitFor(10, TimeUnit.SECONDS), "readelf deadline");
        Files.writeString(output.resolve("readelf-" + role + ".exit"), Integer.toString(child.exitValue()), java.nio.file.StandardOpenOption.CREATE_NEW);
        require(child.exitValue() == 0, "readelf failure: " + role);
        var match = java.util.regex.Pattern.compile("Build ID: ([0-9a-f]+)").matcher(Files.readString(log));
        require(match.find(), "missing build ID: " + role);
        return match.group(1);
    }

    private static LiveStackBootstrap.LiveDeploymentIdentity candidate(String[] args, Path output) throws Exception {
        Path root = Path.of(args[2]).toRealPath();
        var md = java.security.MessageDigest.getInstance("SHA-256");
        for (String source : List.of("profiler-native/src/agent.cpp", "profiler-native/src/live_stack_jni.cpp",
                "profiler-native/include/live_stack_jni.hpp", "profiler-native/src/live_stack_context.cpp",
                "profiler-native/CMakeLists.txt", "platform/src/main/java/io/mazewall/ffi/internal/LiveStackBootstrap.java")) {
            md.update(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            md.update(Files.readAllBytes(root.resolve(source)));
        }
        String source = "sha256:" + java.util.HexFormat.of().formatHex(md.digest());
        Path home = Path.of(System.getProperty("java.home"));
        Path helper = Path.of(LiveStackBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        var paths = new java.util.LinkedHashMap<String, Path>();
        paths.put("agent", Path.of(args[1]).toRealPath()); paths.put("helper", helper);
        paths.put("java", home.resolve("bin/java").toRealPath()); paths.put("release", home.resolve("release").toRealPath());
        paths.put("modules", home.resolve("lib/modules").toRealPath());
        paths.put("libjvm", home.resolve("lib/server/libjvm.so").toRealPath());
        paths.put("libjava", home.resolve("lib/libjava.so").toRealPath());
        paths.put("libnio", home.resolve("lib/libnio.so").toRealPath());
        paths.put("libc", Path.of("/usr/lib/x86_64-linux-gnu/libc.so.6").toRealPath());
        paths.put("loader", Path.of("/usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2").toRealPath());
        var artifacts = new ArrayList<LiveStackBootstrap.ArtifactIdentity>();
        for (var entry : paths.entrySet()) {
            String role = entry.getKey(); Path path = entry.getValue(); String hash = digest(path);
            String evidence = List.of("agent", "helper").contains(role) ? source :
                List.of("libc", "loader").contains(role) ? "ubuntu:libc6:2.43-2ubuntu2.4" :
                "jdk25u:7ec19fb1eda7e42498b7ee4ebde29f77aa961a1c";
            String build = List.of("helper", "release", "modules").contains(role) ? "sha256:" + hash : buildId(path, output, role);
            artifacts.add(new LiveStackBootstrap.ArtifactIdentity(role, path.toString(), hash, build, evidence));
        }
        var identity = new LiveStackBootstrap.LiveDeploymentIdentity(LiveStackBootstrap.Profile.CLOSED_V1,
            java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream().anyMatch(a -> a.endsWith("=live-stack=shared"))
                ? LiveStackBootstrap.Variant.SHARED : LiveStackBootstrap.Variant.ONLY, source, artifacts,
            java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments(),
            List.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator, -1)), "helper",
            List.of(new LiveStackBootstrap.BindingTarget(1, "libnio", 0xe950), new LiveStackBootstrap.BindingTarget(2, "libnio", 0xe9e0)),
            List.of(new LiveStackBootstrap.DependencyTarget("libnio", 0x15ee0, "libc", 0x127990),
                new LiveStackBootstrap.DependencyTarget("libnio", 0x16100, "libc", 0x127a90),
                new LiveStackBootstrap.DependencyTarget("libnio", 0x15c18, "libc", 0x2a980),
                new LiveStackBootstrap.DependencyTarget("libnio", 0x15f40, "libjava", 0x1a0a0),
                new LiveStackBootstrap.DependencyTarget("libjvm", 0x178cad0, "libjvm", 0xb03f90),
                new LiveStackBootstrap.DependencyTarget("libjvm", 0x178cb08, "libjvm", 0xb01220),
                new LiveStackBootstrap.DependencyTarget("libjvm", 0x178cb58, "libjvm", 0xaf7300),
                new LiveStackBootstrap.DependencyTarget("libjvm", 0x178cb70, "libjvm", 0xaf6980),
                new LiveStackBootstrap.DependencyTarget("libjvm", 0x178cb88, "libjvm", 0xb0f040),
                new LiveStackBootstrap.DependencyTarget("libjvm", 0x178cba8, "libjvm", 0xb05e90)),
            args[0].equals("enrollment_minimum") ? List.of(2) : List.of(2, 257), Optional.empty());
        Files.writeString(output.resolve("candidate.txt"), identity.toString(), java.nio.file.StandardOpenOption.CREATE_NEW);
        return identity;
    }

    private static void preparation(String[] args) throws Throwable {
        Path output = Files.createTempDirectory(Files.createDirectories(Path.of(args[3])), args[0] + "-");
        Files.write(output.resolve("launch-argv.bin"), Files.readAllBytes(Path.of("/proc/self/cmdline")), java.nio.file.StandardOpenOption.CREATE_NEW);
        Path fixture = output.resolve("owned-fixture");
        Files.writeString(fixture, "actual JNI enrollment fixture", java.nio.file.StandardOpenOption.CREATE_NEW);
        require(Files.readString(fixture).equals("actual JNI enrollment fixture"), "owned open0 warmup");
        Class<?> dispatcher = Class.forName("sun.nio.fs.UnixNativeDispatcher");
        var openat = dispatcher.getDeclaredMethod("openat", int.class, byte[].class, int.class, int.class);
        var close = dispatcher.getDeclaredMethod("close", int.class);
        openat.setAccessible(true); close.setAccessible(true);
        if (!args[0].equals("deployment_absent_binding")) {
            int fd = (int) openat.invoke(null, -100, fixture.toAbsolutePath().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, 0);
            close.invoke(null, fd);
        }
        var identity = candidate(args, output);
        Files.copy(Path.of("/proc/self/maps"), output.resolve("maps-before-deployment"));
        if (args[0].equals("deployment_rejected_launch")) {
            try { LiveStackBootstrap.checkDeployment(identity); throw new AssertionError("incompatible actual launch accepted"); }
            catch (UnsupportedOperationException | UnsatisfiedLinkError expected) { /* no startup is not late-load readiness */ }
            assertUnready(args[1]); System.out.println("actual incompatible launch refused; zero unready info payload"); return;
        }
        if (args[0].equals("host_runtime_refusal")) {
            require(!System.getProperty("java.runtime.version").equals("25.0.5+8-LTS"), "host refusal requires wrong host runtime");
            try { LiveStackBootstrap.checkDeployment(identity); throw new AssertionError("wrong host accepted"); }
            catch (UnsupportedOperationException expected) { /* explicit host mismatch, not a target positive */ }
            assertUnready(args[1]);
            System.out.println("host runtime mismatch refused; not a positive");
            return;
        }
        require(System.getProperty("java.runtime.version").equals("25.0.5+8-LTS"), "positive requires pinned target");
        assertUnready(args[1]);
        if (args[0].equals("deployment_negative")) deploymentNegatives(identity, args[1]);
        if (args[0].equals("deployment_absent_binding")) {
            refused(() -> LiveStackBootstrap.checkDeployment(identity), "absent actual binding"); assertUnready(args[1]);
            System.out.println("unobserved actual openat0 binding refused"); return;
        }
        if (args[0].equals("deployment_resolved_original") || args[0].equals("deployment_resolved_dependency")) {
            control0(3, 0, null);
            boolean original = args[0].endsWith("original");
            require(control0(original ? 18 : 19, 1, null) == 0, "test-only actual target substitution");
            refused(() -> LiveStackBootstrap.checkDeployment(identity), "resolvedTargetMismatch with matching files/runtime"); assertUnready(args[1]);
            require(control0(original ? 6 : 19, original ? 1 : 0, null) == 0, "restore test-owned substitution");
            require(LiveStackBootstrap.checkDeployment(identity).length == 3, "only actual target mismatch prevented agreement");
            System.out.println("resolvedTargetMismatch: matching actual artifact hashes/version, substituted actual " + (original ? "original" : "stored dependency") + " refused"); return;
        }
        if (args[0].equals("deployment_init_failure")) {
            control0(1, 1, null);
            for (int i = 0; i < 2; i++) {
                try { LiveStackBootstrap.checkDeployment(identity); throw new AssertionError("consumed initialization accepted"); }
                catch (UnsupportedOperationException expected) { /* failed core one-shot must remain terminal */ }
                assertUnready(args[1]);
            }
            require(control0(2, 0, null) == 1, "core initialize must be consumed exactly once");
            require(control0(21, 0, null) == 1, "terminal consumed attempt retains exactly one owned identity");
            System.out.println("terminal initialization failure retained; no retry");
            return;
        }
        if (args[0].equals("deployment_rollback")) {
            for (int point = 1; point <= 4; point++) {
                var exact = new OutOfMemoryError("deployment allocation " + point); control0(7, point, exact);
                try { LiveStackBootstrap.checkDeployment(identity); throw new AssertionError("deployment allocation accepted"); }
                catch (OutOfMemoryError failure) { require(failure == exact, "exact deployment throwable preserved"); }
                require(control0(21, 0, null) == 0 && control0(2, 0, null) == 0, "private candidate rollback leaves no identity pin/consumed initialize"); assertUnready(args[1]);
            }
            control0(7, 0, null);
            var tuple = LiveStackBootstrap.checkDeployment(identity);
            require(tuple[0] == 1 && control0(2, 0, null) == 1 && control0(21, 0, null) == 1, "bootstrap cookie minted only once after all fallible candidate preparation");
            System.out.println("deployment rollback: four allocation/result boundaries, exact OOME, one cookie/initialization/identity owner"); return;
        }
        if (args[0].equals("deployment_rebind_check") || args[0].equals("deployment_rebind_commit")) {
            int point = args[0].endsWith("check") ? 1 : 2;
            long serial = control0(12, 0, null), entered = control0(13, 0, null);
            control0(3, point, null);
            var result = new java.util.concurrent.atomic.AtomicReference<Object>();
            Thread checking = new Thread(() -> { try { result.set(LiveStackBootstrap.checkDeployment(identity)); } catch (Throwable failure) { result.set(failure); } });
            checking.start(); awaitControl(4, point);
            Thread rebinding = new Thread(() -> require(control0(6, 1, null) == 0, "actual RegisterNatives rebind"));
            rebinding.start();
            try { if (point == 1) join(rebinding); else awaitControl(13, entered + 1); }
            finally { control0(5, 0, null); }
            join(checking); join(rebinding);
            require(control0(12, 0, null) > serial, "actual observed rebind advanced core serial");
            if (point == 1) {
                require(result.get() instanceof UnsupportedOperationException, "replacement during Checking refuses");
                assertUnready(args[1]);
                require(LiveStackBootstrap.checkDeployment(identity).length == 3 && control0(2, 0, null) == 1, "rejected candidate may recover before one-shot");
            } else require(result.get() instanceof long[] && control0(2, 0, null) == 1, "commit linearizes before blocked replacement");
            System.out.println(args[0] + ": actual rebind/check ordering passed");
            return;
        }
        long[] process = LiveStackBootstrap.checkDeployment(identity);
        require(process != null && process.length == 3 && process[0] > 0 && process[1] > 0 && process[2] == ProcessHandle.current().pid(), "checked deployment tuple");
        if (args[0].startsWith("guard_fifo_")) { fifoGuard(args, output, process); return; }
        if (args[0].startsWith("guard_")) { runtimeGuard(args, output, process); return; }
        if (args[0].equals("deployment_negative")) {
            var different = new LiveStackBootstrap.LiveDeploymentIdentity(identity.profile(), LiveStackBootstrap.Variant.SHARED,
                identity.sourceEvidenceId(), identity.artifacts(), identity.effectiveVmArguments(), identity.orderedClasspath(), identity.helperArtifactId(),
                identity.bindingTargets(), identity.dependencyTargets(), identity.routeSubset(), identity.retransformFixture());
            refused(() -> LiveStackBootstrap.checkDeployment(different), "write-once identity cannot switch variant");
            require(java.util.Arrays.equals(process, LiveStackBootstrap.checkDeployment(identity)), "rejected later identity preserves exact checked association");
        }
        if (args[0].startsWith("enrollment_") && !List.of("enrollment_shared", "enrollment_minimum").contains(args[0])) {
            enrollmentCases(args, output, process);
            return;
        }
        try (var arena = Arena.ofConfined()) {
            var lookup = SymbolLookup.libraryLookup(Path.of(args[1]), arena);
            var info = arena.allocate(64, 8);
            var infoCall = Linker.nativeLinker().downcallHandle(lookup.find("mazewall_live_info_v1").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            require((int) infoCall.invokeExact(info, 64) == 0, "checked public info");
            require(info.get(ValueLayout.JAVA_LONG, 16) == process[0] && info.get(ValueLayout.JAVA_LONG, 24) == process[1]
                && info.get(ValueLayout.JAVA_INT, 32) == process[2], "info/private agreement");
            if (List.of("enrollment", "enrollment_shared", "enrollment_minimum").contains(args[0])) {
                var lifecycle = Linker.nativeLinker().downcallHandle(lookup.find("mazewall_live_lifecycle_v1").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                var request = arena.allocate(88, 8); var receipt = arena.allocate(88, 8);
                request.fill((byte) 0); request.set(ValueLayout.JAVA_INT, 0, 1); request.set(ValueLayout.JAVA_INT, 4, 88);
                request.set(ValueLayout.JAVA_INT, 8, 1); request.set(ValueLayout.JAVA_LONG, 16, process[0]);
                request.set(ValueLayout.JAVA_LONG, 24, process[1]); request.set(ValueLayout.JAVA_INT, 72, (int) process[2]);
                request.set(ValueLayout.JAVA_INT, 80, 1);
                require((int) lifecycle.invokeExact(request, 88, receipt, 88) == 0, "real Reserve");
                long installation = receipt.get(ValueLayout.JAVA_LONG, 32);
                Class<?>[] owners = args[0].equals("enrollment_minimum") ? new Class<?>[]{dispatcher} : new Class<?>[]{dispatcher, LiveStackContextWorkload.class, Object.class};
                java.lang.reflect.Executable[] methods = {dispatcher.getDeclaredMethod("open0", long.class, int.class, int.class),
                    dispatcher.getDeclaredMethod("openat0", int.class, long.class, int.class, int.class),
                    LiveStackContextWorkload.class.getDeclaredMethod("require", boolean.class, String.class), Object.class.getConstructor()};
                if (args[0].equals("enrollment_minimum")) methods = new java.lang.reflect.Executable[]{methods[0]};
                long[] enrolled = LiveStackBootstrap.enroll(installation, owners, methods);
                require(enrolled.length == 3 + owners.length + methods.length && enrolled[0] > 0 && enrolled[1] == owners.length && enrolled[2] == methods.length, "actual enrollment counts");
                var tokens = new java.util.HashSet<Long>();
                for (int i = 3; i < enrolled.length; i++) require(enrolled[i] > 0 && tokens.add(enrolled[i]), "distinct native token");
                LiveStackBootstrap.checkSeal(installation, enrolled[0]);
                request.set(ValueLayout.JAVA_INT, 8, 2); request.set(ValueLayout.JAVA_INT, 80, 0);
                request.set(ValueLayout.JAVA_LONG, 32, installation); request.set(ValueLayout.JAVA_LONG, 56, enrolled[0]);
                require((int) lifecycle.invokeExact(request, 88, receipt, 88) == 0, "real Seal");
                require(receipt.get(ValueLayout.JAVA_LONG, 32) == installation && receipt.get(ValueLayout.JAVA_LONG, 56) == enrolled[0], "Seal association");
                request.set(ValueLayout.JAVA_INT, 8, 8); request.set(ValueLayout.JAVA_LONG, 56, 0);
                require((int) lifecycle.invokeExact(request, 88, receipt, 88) == 0, "real retirement/cleanup");
            }
        }
        Files.writeString(output.resolve("assertions.txt"), args[0] + ": real production path passed; no Available claimed", java.nio.file.StandardOpenOption.CREATE_NEW);
        System.out.println(args[0] + ": real production path passed; no Available claimed");
    }

    private static void assertUnready(String agent) throws Throwable {
        try (var arena = Arena.ofConfined()) {
            var lookup = SymbolLookup.libraryLookup(Path.of(agent), arena);
            var info = arena.allocate(64, 8); info.fill((byte) 127);
            var call = Linker.nativeLinker().downcallHandle(lookup.find("mazewall_live_info_v1").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            require((int) call.invokeExact(info, 64) == 5 && info.get(ValueLayout.JAVA_INT, 8) == 5, "unready info header");
            for (long i = 16; i < 64; i++) require(info.get(ValueLayout.JAVA_BYTE, i) == 0, "failed info zero payload");
        }
    }
    private static native long control0(int operation, long value, Object object);

    private static final class GuardWorker extends Thread {
        private final long installation;
        private final String path;
        private final boolean enableFailure;
        private Throwable failure;
        GuardWorker(long installation, String path, boolean enableFailure) {
            this.installation = installation; this.path = path; this.enableFailure = enableFailure;
        }
        public void run() {
            try {
                if (enableFailure) {
                    control0(24, 1, null);
                    try { refused(LiveStackBootstrap::prepareWorker, "actual per-thread event-enable failure"); }
                    finally { control0(24, 0, null); }
                    require(control0(26, installation, null) == -1, "failed preparation cannot Register");
                }
                LiveStackBootstrap.prepareWorker();
                require(control0(26, installation, null) > 0, "actual prepared worker Register");
                require(control0(27, 0, null) == 0, "actual current worker Begin");
                long entries = control0(31, 0, null);
                long acquisitions = control0(39, 0, null);
                require(control0(30, 0, path) >= 0, "real original acquired owned fixture FD");
                require(control0(29, 0, null) == 0, "real helper witness and complete Available before actual original");
                require(control0(31, 0, null) == entries + 1, "exact one actual per-thread MethodEntry acknowledgment");
                require(control0(39, 0, null) == acquisitions + 1, "actual live/shared stack acquisition exactly once");
                long bodies = control0(32, 0, null), originals = control0(36, 0, null);
                var exact = new OutOfMemoryError("probe pre-body resource fault");
                control0(7, 0, exact); control0(35, 1, null);
                try {
                    control0(30, 0, path); throw new AssertionError("probe pre-body fault accepted");
                } catch (OutOfMemoryError caught) {
                    require(caught == exact && control0(38, 0, caught) == 1, "exact JNI IsSameObject pre-body throwable");
                } finally { control0(35, 0, null); control0(7, 0, null); }
                require(control0(31, 0, null) == entries + 2 && control0(32, 0, null) == bodies,
                    "probePreBodyException: actual acknowledgment but zero scalar-body calls");
                require(control0(36, 0, null) == originals && control0(29, 0, null) != 0,
                    "probePreBodyException: zero actual originals and unpublished");
                require(control0(39, 0, null) == acquisitions + 1, "pre-body exception captures nothing");
                require(control0(30, 0, path) >= 0 && control0(29, 0, null) == 0,
                    "next activation requires a fresh actual witness");
                require(control0(31, 0, null) == entries + 3 && control0(32, 0, null) == bodies + 1,
                    "same task does not reuse an old witness");
                require(control0(39, 0, null) == acquisitions + 2, "fresh activation acquires exactly once");
                require(control0(40, 0, path) >= 0 && control0(29, 0, null) == 0,
                    "actual openat0 helper witness and complete Available");
                require(control0(31, 0, null) == entries + 4 && control0(39, 0, null) == acquisitions + 3,
                    "openat0 has its own fresh witness and exactly one acquisition");
                require(control0(28, 0, null) == 0, "actual current worker End");
            } catch (Throwable error) { failure = error; }
        }
    }
    private static final class FifoWorker extends Thread {
        final long installation;
        final String path;
        final int operation;
        volatile Throwable failure;
        FifoWorker(long installation, String path, int operation) {
            this.installation = installation; this.path = path; this.operation = operation;
        }
        public void run() {
            try {
                LiveStackBootstrap.prepareWorker();
                require(control0(26, installation, null) > 0, "fifo Register");
                require(control0(27, 0, null) == 0, "fifo Begin");
                require(control0(43, 0, null) == 0, "publish actual request");
                require(control0(operation, 0, path) >= 0, "original FIFO open returned owned descriptor");
                require(control0(36, 0, null) == 1, "exactly one original");
                require(control0(28, 0, null) == 0, "fifo End");
            } catch (Throwable error) { failure = error; }
        }
    }

    private static long fifoThreadStart(long tid) throws Exception {
        String stat = Files.readString(Path.of("/proc/self/task", Long.toString(tid), "stat"));
        int end = stat.lastIndexOf(')');
        require(end >= 0, "thread stat format");
        String[] fields = stat.substring(end + 1).trim().split("\\s+");
        require(fields.length > 19, "thread stat start time");
        return Long.parseLong(fields[19]);
    }

    private static String fifoWitness(long tid, Path fifo, long deadline) throws Exception {
        byte[] expected = (fifo.toString() + "\0").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        require(expected.length <= 4096, "bounded FIFO path");
        Path syscall = Path.of("/proc/self/task", Long.toString(tid), "syscall");
        String last = "unobserved";
        while (System.nanoTime() < deadline) {
            last = Files.readString(syscall).trim();
            String[] fields = last.split("\\s+");
            if (fields.length >= 7 && fields[0].equals("257")) {
                long pathAddress = Long.decode(fields[2]);
                long flags = Long.decode(fields[3]);
                byte[] actual = new byte[expected.length];
                long count = control0(47, pathAddress, actual);
                if (count < 0) throw new AssertionError("checked /proc/self/mem read failed errno=" + -count);
                if ((flags & 3) == 0 && count == actual.length && java.util.Arrays.equals(actual, expected)) return last;
            }
            Thread.sleep(5); // Pace independent kernel-state observations; the deadline only bounds failure.
        }
        throw new AssertionError("no exact x86_64 openat FIFO witness: " + last);
    }

    private static void releaseFifo(FifoWorker worker, Path fifo) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long status;
        do {
            status = control0(46, 0, fifo.toString());
            if (status == 0 || !worker.isAlive()) break;
            if (status != -6) throw new AssertionError("nonblocking FIFO writer failed errno=" + -status);
            Thread.sleep(5); // Bounded ENXIO retry while waiting for the original reader.
        } while (System.nanoTime() < deadline);
        if (worker.isAlive()) require(status == 0, "FIFO reader release deadline");
    }

    private record FifoCopy(int status, byte[] bytes) {}
    private static FifoCopy fifoCopy(String agent, byte[] scalars) throws Exception {
        byte[] input = scalars.clone();
        var task = new java.util.concurrent.FutureTask<FifoCopy>(() -> {
            try (var arena = Arena.ofConfined()) {
                var lookup = SymbolLookup.libraryLookup(Path.of(agent), arena);
                var copy = Linker.nativeLinker().downcallHandle(lookup.find("mazewall_live_copy_v1").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                var request = arena.allocate(88, 8); request.copyFrom(java.lang.foreign.MemorySegment.ofArray(input));
                var snapshot = arena.allocate(2152, 8); snapshot.fill((byte) 0);
                int status;
                try { status = (int) copy.invokeExact(request, 88, snapshot, 2152); }
                catch (Throwable failure) { throw new IllegalStateException("Copy invocation failed", failure); }
                return new FifoCopy(status, snapshot.toArray(ValueLayout.JAVA_BYTE));
            }
        });
        Thread observer = new Thread(task, "fifo-copy-observer"); observer.setDaemon(true); observer.start();
        try { return task.get(10, TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException timeout) { throw new AssertionError("Copy exceeded 10s; observer remains owned by outer container", timeout); }
    }

    private static void fifoGuard(String[] args, Path output, long[] process) throws Throwable {
        require(System.getProperty("os.arch").equals("amd64"), "FIFO witness requires x86_64 syscall ABI");
        try (var lifecycle = new Lifecycle(args[1], process, output); var arena = Arena.ofConfined()) {
            long installation = lifecycle.reserve();
            Class<?> dispatcher = Class.forName("sun.nio.fs.UnixNativeDispatcher");
            Class<?>[] owners = {dispatcher, LiveStackContextWorkload.class, FifoWorker.class, Thread.class};
            var methods = new ArrayList<java.lang.reflect.Executable>();
            for (var owner : owners) methods.addAll(List.of(owner.getDeclaredMethods()));
            long[] enrolled = LiveStackBootstrap.enroll(installation, owners, methods.toArray(java.lang.reflect.Executable[]::new));
            LiveStackBootstrap.checkSeal(installation, enrolled[0]);
            require(lifecycle.invoke(2, installation, enrolled[0]) == 0, "fifo Seal");
            Path fifo = output.resolve("guard-fifo").toAbsolutePath();
            long made = control0(45, 0, fifo.toString());
            require(made == 0, "mkfifo failed errno=" + -made);
            var attrs = Files.readAttributes(fifo, "unix:mode,dev,ino");
            require((((Integer) attrs.get("mode")) & 0170777) == 0010600, "actual restrictive FIFO type and mode");
            int binding = args[0].startsWith("guard_fifo_openat_") ? 2 : 1;
            FifoWorker worker = new FifoWorker(installation, fifo.toString(), binding == 2 ? 40 : 30);
            worker.start();
            Throwable failure = null;
            try {
                byte[] scalar = new byte[88];
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                long status;
                while ((status = control0(44, 0, scalar)) == 1 && worker.isAlive() && System.nanoTime() < deadline)
                    Thread.sleep(5); // Bounded observation of the release-published mailbox.
                require(status == 0, "actual request mailbox unavailable");
                Files.write(output.resolve("fifo-request.bin"), scalar, java.nio.file.StandardOpenOption.CREATE_NEW);
                var request = arena.allocate(88, 8); request.copyFrom(java.lang.foreign.MemorySegment.ofArray(scalar));
                require(request.get(ValueLayout.JAVA_INT, 0) == 1 && request.get(ValueLayout.JAVA_INT, 4) == 88, "request ABI");
                long tid = Integer.toUnsignedLong(request.get(ValueLayout.JAVA_INT, 76));
                require(tid > 0 && request.get(ValueLayout.JAVA_INT, 72) == (int) process[2], "actual worker identity");
                require(worker.isAlive(), "original worker alive before witness");
                long threadStart = fifoThreadStart(tid);
                String before = fifoWitness(tid, fifo, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
                require(worker.isAlive() && fifoThreadStart(tid) == threadStart, "original worker identity before Copy");
                FifoCopy copied = fifoCopy(args[1], scalar);
                require(copied.status() == 0, "blocked original Copy Available");
                var snapshot = java.lang.foreign.MemorySegment.ofArray(copied.bytes());
                require(snapshot.get(ValueLayout.JAVA_INT_UNALIGNED, 0) == 1 && snapshot.get(ValueLayout.JAVA_INT_UNALIGNED, 4) == 2152
                    && snapshot.get(ValueLayout.JAVA_INT_UNALIGNED, 8) == 0, "snapshot ABI and status");
                for (int offset : new int[]{16, 24, 32, 40, 48, 56})
                    require(snapshot.get(ValueLayout.JAVA_LONG_UNALIGNED, offset) == request.get(ValueLayout.JAVA_LONG, offset), "snapshot identity " + offset);
                require(snapshot.get(ValueLayout.JAVA_INT_UNALIGNED, 88) == request.get(ValueLayout.JAVA_INT, 72)
                    && snapshot.get(ValueLayout.JAVA_INT_UNALIGNED, 92) == request.get(ValueLayout.JAVA_INT, 76)
                    && snapshot.get(ValueLayout.JAVA_INT_UNALIGNED, 96) == binding, "snapshot worker and open binding");
                int count = snapshot.get(ValueLayout.JAVA_INT_UNALIGNED, 100);
                require(count > 0 && count <= 128, "bounded nonempty frames");
                var tokens = new java.util.HashSet<Long>(); for (int i = 3; i < enrolled.length; i++) tokens.add(enrolled[i]);
                for (int i = 0; i < count; i++) require(tokens.contains(snapshot.get(ValueLayout.JAVA_LONG_UNALIGNED, 104 + i * 16L)), "enrolled frame token");
                Files.write(output.resolve("fifo-snapshot.bin"), copied.bytes(), java.nio.file.StandardOpenOption.CREATE_NEW);
                for (int offset : new int[]{40, 48, 56}) {
                    long original = request.get(ValueLayout.JAVA_LONG, offset); request.set(ValueLayout.JAVA_LONG, offset, original + 1);
                    try { require(fifoCopy(args[1], request.toArray(ValueLayout.JAVA_BYTE)).status() != 0, "wrong identity rejects " + offset); }
                    finally { request.set(ValueLayout.JAVA_LONG, offset, original); }
                }
                String after = fifoWitness(tid, fifo, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
                require(worker.isAlive() && fifoThreadStart(tid) == threadStart, "original worker identity after Copy");
                require(Files.readAttributes(fifo, "unix:mode,dev,ino").equals(attrs), "original FIFO identity retained");
                Files.writeString(output.resolve("fifo-witness.txt"), "pace=5ms budget=10s\nidentity=" + attrs + "\ntid=" + tid
                    + "\nthreadStart=" + threadStart + "\npath=" + fifo + "\nbefore=" + before + "\nafter=" + after + "\n", java.nio.file.StandardOpenOption.CREATE_NEW);
            } catch (Throwable error) { failure = error; }
            boolean interrupted = Thread.interrupted();
            if (failure instanceof InterruptedException) interrupted = true;
            try { releaseFifo(worker, fifo); }
            catch (Throwable cleanup) { if (cleanup instanceof InterruptedException) interrupted = true; if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
            try { join(worker); }
            catch (Throwable cleanup) { if (cleanup instanceof InterruptedException) interrupted = true; if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
            if (interrupted) Thread.currentThread().interrupt();
            if (worker.failure != null) { if (failure == null) failure = worker.failure; else failure.addSuppressed(worker.failure); }
            if (failure != null) throw failure;
            require(control0(34, 0, null) == 0, "physical ThreadEnd and worker retirement");
            lifecycle.retire(installation);
        }
    }

    private static void runtimeGuard(String[] args, Path output, long[] process) throws Throwable {
        var virtualFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread virtual = Thread.ofVirtual().start(() -> {
            try { refused(LiveStackBootstrap::prepareWorker, "actual virtual worker"); }
            catch (Throwable error) { virtualFailure.set(error); }
        });
        join(virtual);
        if (virtualFailure.get() != null) throw virtualFailure.get();
        try (var lifecycle = new Lifecycle(args[1], process, output)) {
            long installation = lifecycle.reserve();
            Class<?> dispatcher = Class.forName("sun.nio.fs.UnixNativeDispatcher");
            Class<?>[] owners = {dispatcher, LiveStackContextWorkload.class, GuardWorker.class, Thread.class};
            var methods = new ArrayList<java.lang.reflect.Executable>();
            for (var owner : owners) methods.addAll(List.of(owner.getDeclaredMethods()));
            long[] enrolled = LiveStackBootstrap.enroll(installation, owners, methods.toArray(java.lang.reflect.Executable[]::new));
            LiveStackBootstrap.checkSeal(installation, enrolled[0]);
            require(lifecycle.invoke(2, installation, enrolled[0]) == 0, "actual guard Seal");
            Path fixture = output.resolve("guard-fixture"); Files.writeString(fixture, "guard fixture", java.nio.file.StandardOpenOption.CREATE_NEW);
            GuardWorker worker = new GuardWorker(installation, fixture.toString(), args[0].equals("guard_enable_failure"));
            worker.start(); join(worker);
            if (worker.failure != null) throw worker.failure;
            require(control0(34, 0, null) == 0, "physical ThreadEnd before worker retirement");
            lifecycle.retire(installation);
        }
        Files.writeString(output.resolve("guard-assertions.txt"), args[0] + ": actual per-worker preparation, virtual refusal, exact helper acknowledgment and Available; candidate only", java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    private static void deploymentNegatives(LiveStackBootstrap.LiveDeploymentIdentity valid, String agent) throws Throwable {
        for (String test : List.of("artifact", "source", "arguments", "classpath", "helper", "variant", "binding", "dependency", "premain")) {
            var artifacts = new ArrayList<>(valid.artifacts()); var arguments = new ArrayList<>(valid.effectiveVmArguments());
            var classpath = new ArrayList<>(valid.orderedClasspath()); var bindings = new ArrayList<>(valid.bindingTargets());
            var dependencies = new ArrayList<>(valid.dependencyTargets());
            var profile = valid.profile(); var variant = valid.variant(); String helper = valid.helperArtifactId(), source = valid.sourceEvidenceId();
            Optional<LiveStackBootstrap.RetransformFixture> fixture = Optional.empty();
            switch (test) {
                case "artifact" -> { var a = artifacts.get(7); artifacts.set(7, new LiveStackBootstrap.ArtifactIdentity(a.role(), a.canonicalPath(), "0".repeat(64), a.buildId(), a.sourceBuildEvidenceId())); }
                case "source" -> source = "missing";
                case "arguments" -> arguments.remove("-Xshare:off");
                case "classpath" -> classpath.add("/unmeasured.jar");
                case "helper" -> helper = "missing-helper";
                case "variant" -> variant = valid.variant() == LiveStackBootstrap.Variant.ONLY ? LiveStackBootstrap.Variant.SHARED : LiveStackBootstrap.Variant.ONLY;
                case "binding" -> bindings.set(0, new LiveStackBootstrap.BindingTarget(1, "libnio", 0xe951));
                case "dependency" -> dependencies.set(1, new LiveStackBootstrap.DependencyTarget("libnio", 0x16100, "libc", 0x127a91));
                case "premain" -> { profile = LiveStackBootstrap.Profile.RETRANSFORM_TEST_V1; fixture = Optional.of(new LiveStackBootstrap.RetransformFixture("absent", "absent", "Absent", "Absent")); }
                default -> throw new AssertionError(test);
            }
            var rejected = new LiveStackBootstrap.LiveDeploymentIdentity(profile, variant, source, artifacts, arguments, classpath, helper, bindings, dependencies, valid.routeSubset(), fixture);
            refused(() -> LiveStackBootstrap.checkDeployment(rejected), "deployment " + test); assertUnready(agent);
        }
        System.out.println("deployment: nine invalid candidates refused without consuming initialization");
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(10000); require(!thread.isAlive(), "bounded test thread completion");
    }
    private static void awaitControl(int operation, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (control0(operation, 0, null) != expected && System.nanoTime() < deadline) Thread.sleep(1);
        require(control0(operation, 0, null) == expected, "deterministic boundary reached " + operation + ":" + expected);
    }
    public static class Twin { public Twin() {} public void operation() {} }
    public static class Base { public void inherited() {} }
    public static final class Derived extends Base {}
    private static final class TwinLoader extends ClassLoader {
        private final byte[] bytes;
        TwinLoader(byte[] bytes) { super(null); this.bytes = bytes; }
        Class<?> actual() { return defineClass(Twin.class.getName(), bytes, 0, bytes.length); }
    }
    private record Inputs(Class<?>[] owners, java.lang.reflect.Executable[] methods) {}
    private static Inputs inputs(Class<?>... extra) throws Exception {
        Class<?> dispatcher = Class.forName("sun.nio.fs.UnixNativeDispatcher");
        var owners = new ArrayList<Class<?>>(List.of(dispatcher, Object.class)); owners.addAll(List.of(extra));
        var methods = new ArrayList<java.lang.reflect.Executable>(List.of(
            dispatcher.getDeclaredMethod("open0", long.class, int.class, int.class),
            dispatcher.getDeclaredMethod("openat0", int.class, long.class, int.class, int.class), Object.class.getConstructor()));
        for (var actual : extra) { methods.add(actual.getConstructor()); methods.add(actual.getDeclaredMethod("operation")); }
        return new Inputs(owners.toArray(Class<?>[]::new), methods.toArray(java.lang.reflect.Executable[]::new));
    }
    private static final class Lifecycle implements AutoCloseable {
        private final Arena arena = Arena.ofConfined();
        private final java.lang.invoke.MethodHandle call;
        private final java.lang.foreign.MemorySegment request = arena.allocate(88, 8), receipt = arena.allocate(88, 8);
        private final Path output;
        private int sequence;
        private int capacity = 1;
        Lifecycle(String agent, long[] process, Path output) {
            this.output = output;
            var lookup = SymbolLookup.libraryLookup(Path.of(agent), arena);
            call = Linker.nativeLinker().downcallHandle(lookup.find("mazewall_live_lifecycle_v1").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            request.fill((byte) 0); request.set(ValueLayout.JAVA_INT, 0, 1); request.set(ValueLayout.JAVA_INT, 4, 88);
            request.set(ValueLayout.JAVA_LONG, 16, process[0]); request.set(ValueLayout.JAVA_LONG, 24, process[1]); request.set(ValueLayout.JAVA_INT, 72, (int) process[2]);
        }
        int invoke(int operation, long installation, long generation) throws Throwable {
            request.set(ValueLayout.JAVA_INT, 8, operation); request.set(ValueLayout.JAVA_LONG, 32, installation);
            request.set(ValueLayout.JAVA_LONG, 56, generation); request.set(ValueLayout.JAVA_INT, 80, operation == 1 ? capacity : 0);
            receipt.fill((byte) 127);
            int status = (int) call.invokeExact(request, 88, receipt, 88);
            require(status == receipt.get(ValueLayout.JAVA_INT, 8), "receipt/status agreement");
            if (status != 0) for (long i = 16; i < 88; i++) require(receipt.get(ValueLayout.JAVA_BYTE, i) == 0, "failed receipt zero payload");
            Files.write(output.resolve("receipt-" + sequence++ + ".bin"), receipt.toArray(ValueLayout.JAVA_BYTE), java.nio.file.StandardOpenOption.CREATE_NEW);
            return status;
        }
        long reserve() throws Throwable { require(invoke(1, 0, 0) == 0, "Reserve"); return receipt.get(ValueLayout.JAVA_LONG, 32); }
        void wrongProcess() throws Throwable {
            int actual = request.get(ValueLayout.JAVA_INT, 72);
            request.set(ValueLayout.JAVA_INT, 72, actual + 1);
            try { require(invoke(1, 0, 0) == 4 && reason() == 8, "wrong process refuses with zero payload"); }
            finally { request.set(ValueLayout.JAVA_INT, 72, actual); }
        }
        void capacityRetained(boolean retained) throws Throwable {
            capacity = 256;
            try {
                int result = invoke(1, 0, 0);
                if (retained) require(result == 7 && reason() == 14, "cleanup failure retains core reservation capacity");
                else { require(result == 0, "all reservation capacity restored after actual retirement"); retire(receipt.get(ValueLayout.JAVA_LONG, 32)); }
            } finally { capacity = 1; }
        }
        int reason() { return receipt.get(ValueLayout.JAVA_INT, 12); }
        void retire(long installation) throws Throwable { require(invoke(8, installation, 0) == 0, "actual retirement/cleanup"); }
        public void close() { arena.close(); }
    }
    @FunctionalInterface private interface Attempt { void run() throws Throwable; }
    private static void refused(Attempt attempt, String label) throws Throwable {
        try { attempt.run(); throw new AssertionError("accepted: " + label); }
        catch (UnsupportedOperationException expected) { /* explicit production setup refusal */ }
    }
    private static void validateTokens(long[] result, Inputs input) {
        require(result.length == 3 + input.owners().length + input.methods().length && result[0] > 0
            && result[1] == input.owners().length && result[2] == input.methods().length, "enrollment result shape");
        var tokens = new java.util.HashSet<Long>();
        for (int i = 3; i < result.length; i++) require(result[i] > 0 && tokens.add(result[i]), "distinct actual semantic token");
    }
    private static void enrollmentCases(String[] args, Path output, long[] process) throws Throwable {
        Inputs input = inputs();
        try (var lifecycle = new Lifecycle(args[1], process, output)) {
            switch (args[0]) {
                case "enrollment_validation" -> {
                    lifecycle.wrongProcess();
                    for (String test : List.of("nullowners", "nullmethods", "duplicateowner", "duplicatemethod", "emptyowners", "emptymethods", "oversizeowners", "oversizemethods", "nullowner", "nullmethod", "inherited")) {
                        long installation = lifecycle.reserve();
                        Class<?>[] owners = input.owners().clone(); java.lang.reflect.Executable[] methods = input.methods().clone();
                        switch (test) {
                            case "nullowners" -> owners = null;
                            case "nullmethods" -> methods = null;
                            case "duplicateowner" -> owners = new Class<?>[]{owners[0], owners[0]};
                            case "duplicatemethod" -> methods = new java.lang.reflect.Executable[]{methods[0], methods[0]};
                            case "emptyowners" -> owners = new Class<?>[0];
                            case "emptymethods" -> methods = new java.lang.reflect.Executable[0];
                            case "oversizeowners" -> owners = new Class<?>[513];
                            case "oversizemethods" -> methods = new java.lang.reflect.Executable[4097];
                            case "nullowner" -> owners[0] = null;
                            case "nullmethod" -> methods[0] = null;
                            case "inherited" -> { owners = new Class<?>[]{owners[0], Derived.class}; methods[2] = Derived.class.getMethod("inherited"); }
                            default -> throw new AssertionError(test);
                        }
                        Class<?>[] actualOwners = owners; java.lang.reflect.Executable[] actualMethods = methods;
                        refused(() -> LiveStackBootstrap.enroll(installation, actualOwners, actualMethods), test);
                        require(control0(9, 0, null) == 0, "validation rollback refs: " + test);
                        require(lifecycle.invoke(2, installation, 1) == 4, "failed enrollment cannot Seal");
                        lifecycle.retire(installation);
                    }
                    long installation = lifecycle.reserve();
                    var enrolled = LiveStackBootstrap.enroll(installation, input.owners(), input.methods()); validateTokens(enrolled, input);
                    require(control0(14, 0, null) == control0(14, 1, null) && control0(15, 0, null) == control0(15, 1, null), "bootstrap null loader/java.base interned by actual identity");
                    refused(() -> LiveStackBootstrap.enroll(installation, input.owners(), input.methods()), "no second enrollment");
                    LiveStackBootstrap.checkSeal(installation, enrolled[0]); require(lifecycle.invoke(2, installation, enrolled[0]) == 0, "valid constructor/bootstrap Seal");
                    lifecycle.retire(installation);
                }
                case "enrollment_rollback" -> {
                    Inputs rollbackInput = inputs(Twin.class);
                    control0(7, 0, null);
                    long first = lifecycle.reserve(); var baseline = LiveStackBootstrap.enroll(first, rollbackInput.owners(), rollbackInput.methods()); validateTokens(baseline, rollbackInput);
                    long points = control0(8, 0, null); require(points > 8, "all allocation/result boundaries counted"); lifecycle.retire(first);
                    for (long point = 1; point <= points; point++) {
                        long installation = lifecycle.reserve(); var exact = new OutOfMemoryError("actual pending throwable at boundary " + point);
                        control0(7, point, exact);
                        try { LiveStackBootstrap.enroll(installation, rollbackInput.owners(), rollbackInput.methods()); throw new AssertionError("allocation accepted " + point); }
                        catch (OutOfMemoryError actual) { require(actual == exact, "exact pending throwable survived rollback " + point); }
                        require(control0(9, 0, null) == 0, "no partial JNI refs " + point);
                        require(control0(22, 0, null) == 0, "no partial Seal association " + point);
                        require(lifecycle.invoke(2, installation, 1) == 4, "no partial core enrollment " + point);
                        lifecycle.retire(installation);
                    }
                    control0(7, 0, null);
                    long last = lifecycle.reserve(); var fresh = LiveStackBootstrap.enroll(last, rollbackInput.owners(), rollbackInput.methods()); validateTokens(fresh, rollbackInput);
                    require(fresh[0] > baseline[0], "fresh generation/no semantic ID reuse after rollback");
                    lifecycle.retire(last); require(control0(9, 0, null) == 0, "successful transfer cleaned exactly once");
                    require(control0(22, 0, null) == 0, "successful retirement removed scalar association"); lifecycle.capacityRetained(false);
                    System.out.println("partialEnrollmentRollback: " + points + " allocation/result boundaries; exact OOME identity; no refs/core/reservation leak");
                }
                case "enrollment_retention" -> retention(lifecycle, output);
                case "enrollment_null_reference" -> {
                    long installation = lifecycle.reserve(); control0(23, 1, null);
                    try { LiveStackBootstrap.enroll(installation, input.owners(), input.methods()); throw new AssertionError("null JNI ref without pending exception must throw OOME"); }
                    catch (OutOfMemoryError expected) { /* JNI NULL without a pending exception is still an allocation failure */ }
                    finally { control0(23, 0, null); }
                    require(control0(9, 0, null) == 0 && control0(22, 0, null) == 0, "null-reference rollback has no JNI/map owner");
                    require(lifecycle.invoke(2, installation, 1) == 4, "null-reference failure cannot publish core enrollment"); lifecycle.retire(installation); lifecycle.capacityRetained(false);
                }
                case "enrollment_helper_mutation" -> {
                    long installation = lifecycle.reserve(); var enrolled = LiveStackBootstrap.enroll(installation, input.owners(), input.methods());
                    long serial = control0(12, 0, null);
                    require(control0(20, 0, null) == 0 && control0(12, 0, null) > serial, "actual changed helper binding delivered/invalidated");
                    refused(() -> LiveStackBootstrap.checkSeal(installation, enrolled[0]), "observed helper rebinding");
                    require(lifecycle.invoke(2, installation, enrolled[0]) != 0, "helper mutation cannot Seal");
                    lifecycle.retire(installation);
                }
                case "enrollment_postseal_replacement" -> {
                    long installation = lifecycle.reserve(); var enrolled = LiveStackBootstrap.enroll(installation, input.owners(), input.methods());
                    LiveStackBootstrap.checkSeal(installation, enrolled[0]);
                    require(control0(11, 0, Twin.class) == 0, "actual replacement after private checkSeal");
                    require(lifecycle.invoke(2, installation, enrolled[0]) == 4 && lifecycle.reason() == 9, "core op2 serial check remains authoritative");
                    refused(() -> LiveStackBootstrap.checkSeal(installation, enrolled[0]), "old generation never revives"); lifecycle.retire(installation);
                }
                case "enrollment_revocation" -> {
                    long installation = lifecycle.reserve(); control0(3, 3, null);
                    var result = new java.util.concurrent.atomic.AtomicReference<Object>();
                    Thread enrolling = new Thread(() -> { try { result.set(LiveStackBootstrap.enroll(installation, input.owners(), input.methods())); } catch (Throwable failure) { result.set(failure); } });
                    enrolling.start(); awaitControl(4, 3);
                    try {
                        require(lifecycle.invoke(6, installation, 0) == 0, "revocation linearizes while enrollment owns admin gate");
                        require(lifecycle.invoke(8, installation, 0) == 3, "retirement cannot acquire pending enrollment ownership");
                        lifecycle.capacityRetained(true);
                    } finally { control0(5, 0, null); }
                    join(enrolling); require(result.get() instanceof UnsupportedOperationException && control0(9, 0, null) == 0, "failed commit owns/releases only pending refs");
                    lifecycle.retire(installation); lifecycle.capacityRetained(false);
                }
                case "enrollment_replacement", "enrollment_seal_race" -> {
                    long installation = lifecycle.reserve();
                    int point = args[0].equals("enrollment_replacement") ? 3 : 4;
                    long[] enrolled = point == 4 ? LiveStackBootstrap.enroll(installation, input.owners(), input.methods()) : null;
                    control0(3, point, null);
                    var result = new java.util.concurrent.atomic.AtomicReference<Object>();
                    Thread preparation = new Thread(() -> {
                        try { if (point == 3) result.set(LiveStackBootstrap.enroll(installation, input.owners(), input.methods()));
                              else { LiveStackBootstrap.checkSeal(installation, enrolled[0]); result.set(Boolean.TRUE); } }
                        catch (Throwable failure) { result.set(failure); }
                    });
                    preparation.start(); awaitControl(4, point);
                    long before = control0(12, 0, null);
                    require(control0(11, 0, Twin.class) == 0 && control0(12, 0, null) > before, "actual nonnull replacement hook");
                    control0(5, 0, null); join(preparation);
                    require(result.get() instanceof UnsupportedOperationException, "replacement cannot revive staging/sealing generation");
                    require(lifecycle.invoke(2, installation, point == 4 ? enrolled[0] : 1) == 4, "core Seal remains authoritative after replacement");
                    lifecycle.retire(installation); require(control0(9, 0, null) == 0, "race retirement releases retained owner");
                }
                default -> throw new AssertionError(args[0]);
            }
        }
        System.out.println(args[0] + ": actual JNI/state assertions passed");
    }
    private record Retained(long generation, java.lang.ref.WeakReference<Class<?>> first,
            java.lang.ref.WeakReference<Class<?>> second, java.lang.ref.WeakReference<ClassLoader> loader) {}
    private static Retained retain(long installation) throws Exception {
        byte[] bytes;
        try (var in = LiveStackContextWorkload.class.getResourceAsStream("LiveStackContextWorkload$Twin.class")) { bytes = java.util.Objects.requireNonNull(in).readAllBytes(); }
        var firstLoader = new TwinLoader(bytes); var secondLoader = new TwinLoader(bytes);
        var first = firstLoader.actual(); var second = secondLoader.actual();
        var input = inputs(first, second); var enrolled = LiveStackBootstrap.enroll(installation, input.owners(), input.methods()); validateTokens(enrolled, input);
        require(enrolled[5] != enrolled[6] && enrolled[10] != enrolled[12], "same binary names, distinct Class/method tokens");
        require(control0(14, 2, null) != control0(14, 3, null) && control0(15, 2, null) != control0(15, 3, null), "actual distinct loader/module intern tokens");
        return new Retained(enrolled[0], new java.lang.ref.WeakReference<>(first), new java.lang.ref.WeakReference<>(second), new java.lang.ref.WeakReference<>(firstLoader));
    }
    private static void retention(Lifecycle lifecycle, Path output) throws Throwable {
        long installation = lifecycle.reserve(); var retained = retain(installation);
        for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(20); }
        require(retained.first().get() != null && retained.second().get() != null && retained.loader().get() != null, "actual global refs retain classes/loaders across GC");
        LiveStackBootstrap.checkSeal(installation, retained.generation()); require(lifecycle.invoke(2, installation, retained.generation()) == 0, "actual two-loader Seal");
        long refs = control0(9, 0, null); require(refs > 0, "actual retained global refs counted");
        require(control0(17, installation, null) == ((3L << 32) | 22) && control0(9, 0, null) == refs, "unattached cleanup retains owner for retry without attach");
        lifecycle.capacityRetained(true);
        control0(10, 1, null); int attempts = 0;
        while (lifecycle.invoke(8, installation, 0) != 0) {
            require(lifecycle.reason() == 22 && ++attempts < 32, "partial cleanup remains bounded/retryable");
            require(control0(9, 0, null) < refs, "partial cleanup made progress"); refs = control0(9, 0, null);
            lifecycle.capacityRetained(true);
        }
        control0(10, -1, null); require(control0(9, 0, null) == 0, "all transferred refs physically released once");
        lifecycle.capacityRetained(false);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while ((retained.first().get() != null || retained.second().get() != null || retained.loader().get() != null) && System.nanoTime() < deadline) { System.gc(); Thread.sleep(20); }
        require(retained.first().get() == null && retained.second().get() == null && retained.loader().get() == null, "actual refs collectible after final retirement");
        Files.writeString(output.resolve("retention.txt"), "real two-loader GC retention, unattached refusal and " + (attempts + 1) + " cleanup attempts passed", java.nio.file.StandardOpenOption.CREATE_NEW);
    }
    private static void launchMatrix(String[] args) throws Exception {
        Path output = Files.createTempDirectory(Files.createDirectories(Path.of(args[3])), "actual-launch-");
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin/java"); String jar = System.getProperty("java.class.path");
        Path directory = Path.of(args[1]).getParent();
        for (String test : List.of("no-startup", "missing-attach", "missing-dynamic", "missing-sharing", "missing-jvmci", "extra-agent", "renamed-agent", "substituted-agent", "extra-classpath", "named-module")) {
            Path own = Files.createDirectory(output.resolve(test));
            var command = new ArrayList<String>(List.of(javaExecutable.toString(), "-XX:+DisableAttachMechanism", "-XX:-EnableDynamicAgentLoading",
                "-Xshare:off", "-XX:+UnlockExperimentalVMOptions", "-XX:-EnableJVMCI", "--enable-native-access=ALL-UNNAMED",
                "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED", "-XX:-CreateCoredumpOnCrash", "-XX:ErrorFile=" + own.resolve("hs_err_pid%p.log")));
            switch (test) {
                case "missing-attach" -> command.remove("-XX:+DisableAttachMechanism");
                case "missing-dynamic" -> command.remove("-XX:-EnableDynamicAgentLoading");
                case "missing-sharing" -> command.remove("-Xshare:off");
                case "missing-jvmci" -> command.remove("-XX:-EnableJVMCI");
                default -> { /* same fixed VM arguments otherwise */ }
            }
            if (!List.of("no-startup", "substituted-agent").contains(test)) command.add("-agentpath:" + args[1] + "=live-stack=only");
            if (List.of("extra-agent", "substituted-agent").contains(test)) command.add("-agentpath:" + directory.resolve("liblive_stack_participant.so"));
            if (test.equals("renamed-agent")) command.add("-agentpath:" + directory.resolve("renamed-participant.so"));
            if (test.equals("named-module")) command.addAll(List.of("--module-path", jar, "--module", "live.stack.fixture/" + LiveStackContextWorkload.class.getName()));
            else command.addAll(List.of("-cp", test.equals("extra-classpath") ? jar + java.io.File.pathSeparator + directory.resolve("liblive_stack_participant.so") : jar, LiveStackContextWorkload.class.getName()));
            command.addAll(List.of("deployment_rejected_launch", args[1], args[2], own.toString()));
            Files.writeString(own.resolve("launch.txt"), command.toString(), java.nio.file.StandardOpenOption.CREATE_NEW);
            Path log = own.resolve("child.log");
            var child = new ProcessBuilder(command).directory(own.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean exited = child.waitFor(15, TimeUnit.SECONDS);
            if (!exited) { child.destroyForcibly(); child.waitFor(); }
            Files.writeString(own.resolve("exit.txt"), Integer.toString(child.exitValue()), java.nio.file.StandardOpenOption.CREATE_NEW);
            require(exited, "bounded actual launch " + test);
            String text = Files.readString(log);
            if (test.equals("named-module")) require(child.exitValue() != 0 && text.contains("wrong helper module"), "actual named helper module refusal: " + text);
            else require(child.exitValue() == 0 && text.contains("actual incompatible launch refused"), "actual launch " + test + ": " + text);
        }
        System.out.println("actual launch matrix: 10 incompatible startup/flag/participant/classpath/module cases refused");
    }
}
