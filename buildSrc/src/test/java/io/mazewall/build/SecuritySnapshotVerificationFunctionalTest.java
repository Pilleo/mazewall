package io.mazewall.build;

import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.GradleRunner;

/** Exercises the verifier using the actual scanner plugin's H2 driver, without adding dependencies. */
public final class SecuritySnapshotVerificationFunctionalTest {
    private SecuritySnapshotVerificationFunctionalTest() {}

    public static void main(String[] args) throws Exception {
        verify();
    }

    static void verify() throws Exception {
        Path project = Files.createTempDirectory("security-snapshot-fixture-");
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'snapshot-fixture'\n");
        String classes = Path.of(VerifySpotBugsReport.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .toAbsolutePath().toString().replace("\\", "\\\\").replace("'", "\\'");
        Files.writeString(project.resolve("build.gradle"), """
            buildscript { dependencies { classpath files('%s') } }
            plugins { id 'org.owasp.dependencycheck' version '13.0.0' }
            tasks.register('exerciseSnapshotVerifier') {
                doLast {
                    def driver = Class.forName('org.h2.Driver', true, org.owasp.dependencycheck.gradle.tasks.ConfiguredTask.class.classLoader).getDeclaredConstructor().newInstance()
                    def verifier = Class.forName('io.mazewall.build.SecuritySnapshotVerifier')
                    def verify = verifier.getMethod('verify', java.nio.file.Path, java.sql.Driver, java.util.Properties, java.time.Instant)
                    def now = java.time.Instant.parse('2026-11-15T12:00:00Z')
                    def props = new Properties()
                    props.setProperty('user', 'dcuser')
                    props.setProperty('password', 'DC-Pass1337!')
                    def currentTime = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
                    def cases = [
                        ['dependency-check-data', '5.6', currentTime, currentTime, 1, true],
                        ['fresh', '5.6', '2026-11-15T11:00:00Z', '2026-11-14T00:00:00Z', 1, true],
                        ['boundary', '5.6', '2026-11-15T08:00:00Z', '2026-11-14T00:00:00Z', 1, true],
                        ['stale', '5.6', '2026-11-15T07:59:59Z', '2026-11-14T00:00:00Z', 1, false],
                        ['future', '5.6', '2026-11-15T12:00:01Z', '2026-11-14T00:00:00Z', 1, false],
                        ['modifiedFuture', '5.6', '2026-11-15T11:00:00Z', '2026-11-15T12:00:01Z', 1, false],
                        ['modifiedAfterCheck', '5.6', '2026-11-15T11:00:00Z', '2026-11-15T11:30:00Z', 1, false],
                        ['malformed', '5.6', 'invalid', '2026-11-14T00:00:00Z', 1, false],
                        ['malformedModified', '5.6', '2026-11-15T11:00:00Z', 'invalid', 1, false],
                        ['missingChecked', '5.6', null, '2026-11-14T00:00:00Z', 1, false],
                        ['missingModified', '5.6', '2026-11-15T11:00:00Z', null, 1, false],
                        ['wrongSchema', '5.5', '2026-11-15T11:00:00Z', '2026-11-14T00:00:00Z', 1, false],
                        ['empty', '5.6', '2026-11-15T11:00:00Z', '2026-11-14T00:00:00Z', 0, false]
                    ]
                    cases.each { name, schema, checked, modified, count, expected ->
                        def dir = file(name); assert dir.mkdir()
                        def db = new File(dir, 'odc')
                        def connection = driver.connect('jdbc:h2:file:' + db.absolutePath, props)
                        def statement = connection.createStatement()
                        statement.execute('CREATE TABLE properties (id VARCHAR(50) PRIMARY KEY, `value` VARCHAR(500))')
                        statement.execute('CREATE TABLE vulnerability (id INT PRIMARY KEY, cve VARCHAR(20))')
                        def insert = connection.prepareStatement('INSERT INTO properties VALUES (?, ?)')
                        [['version', schema], ['nvd.api.last.checked', checked], ['nvd.api.last.modified', modified]].each { key, value ->
                            if (value != null) { insert.setString(1, key); insert.setString(2, value); insert.executeUpdate() }
                        }
                        if (count) statement.execute("INSERT INTO vulnerability VALUES (1, 'CVE-2026-1234')")
                        insert.close(); statement.close(); connection.close()
                        def file = new File(dir, 'odc.mv.db').toPath()
                        def bytes = java.nio.file.Files.readAllBytes(file)
                        def mtime = java.nio.file.Files.getLastModifiedTime(file)
                        boolean accepted = true
                        def verificationTime = name == 'dependency-check-data' ? java.time.Instant.now() : now
                        try { verify.invoke(null, dir.toPath(), driver, props, verificationTime) }
                        catch (java.lang.reflect.InvocationTargetException failure) {
                            assert failure.cause instanceof IllegalArgumentException
                            assert failure.cause.message == 'Invalid security snapshot'
                            accepted = false
                        }
                        assert accepted == expected : "Unexpected verdict: $name"
                        assert java.util.Arrays.equals(bytes, java.nio.file.Files.readAllBytes(file)) : "Database bytes changed: $name"
                        assert mtime == java.nio.file.Files.getLastModifiedTime(file) : "Database mtime changed: $name"
                        assert dir.list().toList().sort() == ['odc.mv.db'] : "Unexpected DB side files: $name"
                    }
                    def reject = { path ->
                        try { verify.invoke(null, path, driver, props, now); assert false : 'Invalid path accepted' }
                        catch (java.lang.reflect.InvocationTargetException failure) {
                            assert failure.cause instanceof IllegalArgumentException
                            assert failure.cause.message == 'Invalid security snapshot'
                        }
                    }
                    reject(file('missing').toPath())
                    def corrupt = file('corrupt'); assert corrupt.mkdir()
                    new File(corrupt, 'odc.mv.db').bytes = [1, 2, 3] as byte[]
                    reject(corrupt.toPath())
                    def nonregular = file('nonregular'); assert nonregular.mkdir()
                    assert new File(nonregular, 'odc.mv.db').mkdir()
                    reject(nonregular.toPath())
                    def symlink = file('linked').toPath()
                    java.nio.file.Files.createSymbolicLink(symlink, file('fresh').toPath())
                    reject(symlink)
                    def unsafe = file('unsafe;MODE=MySQL'); assert unsafe.mkdir()
                    reject(unsafe.toPath())
                }
            }
            """.formatted(classes));
        var result = GradleRunner.create().withProjectDir(project.toFile())
            .withArguments("exerciseSnapshotVerifier", "--offline", "--no-configuration-cache", "--stacktrace",
                "--gradle-user-home", gradleUserHome())
            .build();
        if (!result.getOutput().contains("BUILD SUCCESSFUL")) throw new AssertionError("Snapshot fixture did not run");
        verifyScanWiring(project);
        verifyKeylessAnalyzer(project);
    }

    private static void verifyScanWiring(Path scratch) throws Exception {
        Path repository = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (repository.getFileName().toString().equals("buildSrc")) repository = repository.getParent();
        Path init = scratch.resolve("scan-contract.gradle");
        Files.writeString(init, """
            gradle.projectsEvaluated {
                if (gradle.rootProject.projectDir.canonicalFile != new File(new URI('%s')).canonicalFile) return
                def root = gradle.rootProject
                def scanner = root.tasks.named('dependencyCheckAnalyze').get()
                def extension = root.extensions.getByName('dependencyCheck')
                assert !extension.autoUpdate.get() : 'Scan extension must not update'
                assert !scanner.autoUpdate.get() : 'Actual scan task must not update'
                assert scanner.taskDependencies.getDependencies(scanner).any { it.name == 'verifySecuritySnapshot' }
            }
            """.formatted(repository.toRealPath().toUri().toASCIIString()));
        var runner = GradleRunner.create().withProjectDir(repository.toFile())
            .withArguments("dependencyCheckAnalyze", "--dry-run", "--offline", "--no-configuration-cache",
                "-Pmazewall.securitySnapshotScan=true", "--init-script", init.toString(), "--gradle-user-home", gradleUserHome());
        String output = runner.build().getOutput();
        if (!output.contains(":verifySecuritySnapshot") || !output.contains(":dependencyCheckAnalyze")) {
            throw new AssertionError("Scan must schedule the verifier before the actual analyzer: " + output);
        }
        var invalid = GradleRunner.create().withProjectDir(repository.toFile())
            .withArguments("help", "--offline", "--no-configuration-cache", "-Pmazewall.securitySnapshotScan=invalid",
                "--gradle-user-home", gradleUserHome())
            .buildAndFail();
        if (!invalid.getOutput().contains("mazewall.securitySnapshotScan")) {
            throw new AssertionError("Invalid scan mode must fail configuration");
        }
        Path updateInit = scratch.resolve("update-contract.gradle");
        Files.writeString(updateInit, """
            gradle.projectsEvaluated {
                if (gradle.rootProject.projectDir.canonicalFile != new File(new URI('%s')).canonicalFile) return
                def root = gradle.rootProject
                def updater = root.tasks.named('dependencyCheckUpdate').get()
                def extension = root.extensions.getByName('dependencyCheck')
                def expected = gradle.startParameter.projectProperties['mazewall.securitySnapshotUpdate'] == 'true'
                assert (extension.nvd.validForHours.orNull == 0) == expected
                assert (updater.nvd.validForHours.orNull == 0) == expected
                if (expected) {
                    assert extension.nvd.resultsPerPage.get() == 500
                    assert updater.nvd.resultsPerPage.get() == 500
                }
            }
            """.formatted(repository.toRealPath().toUri().toASCIIString()));
        for (String value : new String[] {"true", "false"}) {
            GradleRunner.create().withProjectDir(repository.toFile())
                .withArguments("help", "--offline", "--no-configuration-cache", "--init-script", updateInit.toString(),
                    "-Pmazewall.securitySnapshotUpdate=" + value, "--gradle-user-home", gradleUserHome()).build();
        }
        GradleRunner.create().withProjectDir(repository.toFile())
            .withArguments("help", "--offline", "--no-configuration-cache", "--init-script", updateInit.toString(),
                "--gradle-user-home", gradleUserHome()).build();
        var invalidUpdate = GradleRunner.create().withProjectDir(repository.toFile())
            .withArguments("help", "--offline", "--no-configuration-cache", "-Pmazewall.securitySnapshotUpdate=invalid",
                "--gradle-user-home", gradleUserHome()).buildAndFail();
        if (!invalidUpdate.getOutput().contains("mazewall.securitySnapshotUpdate")) {
            throw new AssertionError("Invalid update mode must fail configuration");
        }
    }

    private static void verifyKeylessAnalyzer(Path scratch) throws Exception {
        Path repository = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (repository.getFileName().toString().equals("buildSrc")) repository = repository.getParent();
        try (var listener = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(100);
            Path init = scratch.resolve("offline-analyzer.gradle");
            Files.writeString(init, """
                gradle.projectsEvaluated {
                    if (gradle.rootProject.projectDir.canonicalFile != new File(new URI('%s')).canonicalFile) return
                    def task = gradle.rootProject.tasks.named('dependencyCheckAnalyze').get()
                    task.nvd.endpoint.set('http://127.0.0.1:%d/')
                }
                """.formatted(repository.toRealPath().toUri().toASCIIString(), listener.getLocalPort()));
            var result = GradleRunner.create().withProjectDir(repository.toFile())
                .withEnvironment(java.util.Map.of("CI", "true", "GRADLE_USER_HOME", scratch.toString(),
                    "JAVA_HOME", System.getProperty("java.home"), "PATH", "/usr/bin:/bin", "HOME", scratch.toString()))
                .withArguments("dependencyCheckAnalyze", "--offline", "--no-configuration-cache",
                    "-Pmazewall.securitySnapshotScan=true", "--init-script", init.toString(),
                    "--gradle-user-home", gradleUserHome()).buildAndFail();
            var verified = result.task(":verifySecuritySnapshot");
            var analyzed = result.task(":dependencyCheckAnalyze");
            if (verified == null || verified.getOutcome() != org.gradle.testkit.runner.TaskOutcome.SUCCESS
                || analyzed == null || analyzed.getOutcome() != org.gradle.testkit.runner.TaskOutcome.FAILED) {
                throw new AssertionError("Keyless analyzer did not execute after verification: " + result.getOutput());
            }
            try {
                listener.accept().close();
                throw new AssertionError("Offline analysis contacted the NVD endpoint");
            } catch (java.net.SocketTimeoutException expected) {
                // No update request reached the endpoint.
            }
        }
    }

    private static String gradleUserHome() {
        String home = System.getProperty("mazewall.test.gradleUserHome");
        if (home == null || home.isBlank()) throw new AssertionError("Missing existing Gradle fixture home");
        return home;
    }
}
