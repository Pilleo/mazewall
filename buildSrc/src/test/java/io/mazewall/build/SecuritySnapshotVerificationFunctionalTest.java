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
                            def expectedCode = [stale: 'STALE', future: 'TIME_ORDER', modifiedFuture: 'TIME_ORDER',
                                modifiedAfterCheck: 'TIME_ORDER', malformed: 'METADATA', malformedModified: 'METADATA',
                                missingChecked: 'METADATA', missingModified: 'METADATA', wrongSchema: 'SCHEMA', empty: 'EMPTY_CVES'][name]
                            assert expectedCode != null
                            def message = failure.cause.message
                            assert message.startsWith('Invalid security snapshot: ' + expectedCode)
                            assert failure.cause.cause == null && failure.cause.stackTrace.length == 0 && failure.cause.suppressed.length == 0
                            assert !message.contains(props.getProperty('password')) && !message.contains(dir.absolutePath)
                            if (name == 'stale') {
                                assert message.contains('schema=5.6') && message.contains('cveCount=1')
                                assert message.contains('checked=2026-11-15T07:59:59Z')
                                assert message.contains('modified=2026-11-14T00:00:00Z')
                            }
                            accepted = false
                        }
                        assert accepted == expected : "Unexpected verdict: $name"
                        assert java.util.Arrays.equals(bytes, java.nio.file.Files.readAllBytes(file)) : "Database bytes changed: $name"
                        assert mtime == java.nio.file.Files.getLastModifiedTime(file) : "Database mtime changed: $name"
                        assert dir.list().toList().sort() == ['odc.mv.db'] : "Unexpected DB side files: $name"
                    }
                    def reject = { path, code ->
                        try { verify.invoke(null, path, driver, props, now); assert false : 'Invalid path accepted' }
                        catch (java.lang.reflect.InvocationTargetException failure) {
                            assert failure.cause instanceof IllegalArgumentException
                            assert failure.cause.message == 'Invalid security snapshot: ' + code
                            assert failure.cause.cause == null && failure.cause.stackTrace.length == 0 && failure.cause.suppressed.length == 0
                            assert !failure.cause.message.contains(props.getProperty('password'))
                            assert !failure.cause.message.contains(path.toString())
                        }
                    }
                    reject(file('missing').toPath(), 'PATH_OWNERSHIP')
                    def corrupt = file('corrupt'); assert corrupt.mkdir()
                    new File(corrupt, 'odc.mv.db').bytes = [1, 2, 3] as byte[]
                    reject(corrupt.toPath(), 'DATABASE')
                    def nonregular = file('nonregular'); assert nonregular.mkdir()
                    assert new File(nonregular, 'odc.mv.db').mkdir()
                    reject(nonregular.toPath(), 'PATH_OWNERSHIP')
                    def symlink = file('linked').toPath()
                    java.nio.file.Files.createSymbolicLink(symlink, file('fresh').toPath())
                    reject(symlink, 'PATH_OWNERSHIP')
                    def unsafe = file('unsafe;MODE=MySQL'); assert unsafe.mkdir()
                    reject(unsafe.toPath(), 'PATH_OWNERSHIP')
                    def wrongPassword = new Properties(); wrongPassword.putAll(props)
                    wrongPassword.setProperty('password', 'secret-unique-do-not-log')
                    try { verify.invoke(null, file('fresh').toPath(), driver, wrongPassword, now); assert false }
                    catch (java.lang.reflect.InvocationTargetException failure) {
                        assert failure.cause.message == 'Invalid security snapshot: DATABASE'
                        assert failure.cause.cause == null && failure.cause.stackTrace.length == 0 && failure.cause.suppressed.length == 0
                        assert !failure.cause.message.contains('secret-unique-do-not-log')
                    }
                    def closingDriver = java.lang.reflect.Proxy.newProxyInstance(
                        java.sql.Driver.class.classLoader, [java.sql.Driver] as Class[],
                        { proxy, method, args ->
                            def value
                            try { value = method.invoke(driver, args) }
                            catch (java.lang.reflect.InvocationTargetException wrapped) { throw wrapped.cause }
                            if (method.name != 'connect' || value == null) return value
                            def connection = value
                            return java.lang.reflect.Proxy.newProxyInstance(
                                java.sql.Connection.class.classLoader, [java.sql.Connection] as Class[],
                                { inner, connectionMethod, connectionArgs ->
                                    try {
                                        def result = connectionMethod.invoke(connection, connectionArgs)
                                        if (connectionMethod.name == 'close') throw new java.sql.SQLException('secret-close-must-not-escape')
                                        return result
                                    } catch (java.lang.reflect.InvocationTargetException wrapped) { throw wrapped.cause }
                                } as java.lang.reflect.InvocationHandler)
                        } as java.lang.reflect.InvocationHandler)
                    try { verify.invoke(null, file('stale').toPath(), closingDriver, props, now); assert false }
                    catch (java.lang.reflect.InvocationTargetException failure) {
                        assert failure.cause.message.startsWith('Invalid security snapshot: STALE')
                        assert failure.cause.cause == null && failure.cause.stackTrace.length == 0
                        assert failure.cause.suppressed.length == 0
                        assert !failure.cause.message.contains('secret-close-must-not-escape')
                    }
                    def failingDriver = { failure ->
                        java.lang.reflect.Proxy.newProxyInstance(java.sql.Driver.class.classLoader, [java.sql.Driver] as Class[],
                            { proxy, method, args -> throw failure } as java.lang.reflect.InvocationHandler)
                    }
                    try { verify.invoke(null, file('fresh').toPath(), failingDriver(new LinkageError('synthetic-secret-linkage')), props, now); assert false }
                    catch (java.lang.reflect.InvocationTargetException failure) {
                        assert failure.cause.message == 'Invalid security snapshot: DATABASE'
                        assert failure.cause.cause == null && failure.cause.stackTrace.length == 0 && failure.cause.suppressed.length == 0
                    }
                    def fatal = new InternalError('synthetic-fatal')
                    try { verify.invoke(null, file('fresh').toPath(), failingDriver(fatal), props, now); assert false }
                    catch (java.lang.reflect.InvocationTargetException failure) { assert failure.cause.is(fatal) }
                    def taskType = Class.forName('io.mazewall.build.VerifySecuritySnapshotTask')
                    def task = tasks.create('verifyTaskDiagnostics', taskType)
                    task.dataDirectory.set(file('stale').absolutePath)
                    task.username.set(props.getProperty('user')); task.password.set(props.getProperty('password'))
                    task.connectionString.set('jdbc:h2:file:%%s;AUTOCOMMIT=ON;CACHE_SIZE=65536;RETENTION_TIME=1000;MAX_COMPACT_TIME=10000;')
                    task.driverName.set('org.h2.Driver')
                    task.driverClasspath.from(new File(driver.class.protectionDomain.codeSource.location.toURI()))
                    try { task.verifySnapshot(); assert false : 'Stale task accepted' }
                    catch (IllegalArgumentException failure) {
                        assert failure.message.startsWith('Invalid security snapshot: STALE')
                        assert failure.cause == null && failure.stackTrace.length == 0 && failure.suppressed.length == 0
                        assert !failure.message.contains(props.getProperty('password'))
                    }
                    def badJar = file('bad-driver.jar')
                    new java.util.jar.JarOutputStream(new FileOutputStream(badJar)).withCloseable { jar ->
                        jar.putNextEntry(new java.util.jar.JarEntry('org/h2/Driver.class'))
                        jar.write([0, 1, 2, 3] as byte[]); jar.closeEntry()
                    }
                    task.driverClasspath.setFrom(badJar)
                    try { task.verifySnapshot(); assert false : 'Bad driver class accepted' }
                    catch (IllegalArgumentException failure) {
                        assert failure.message == 'Invalid security snapshot: CONFIGURATION'
                        assert failure.cause == null && failure.stackTrace.length == 0 && failure.suppressed.length == 0
                    }
                    def fatalClasses = file('fatal-driver-classes'); assert fatalClasses.mkdir()
                    def fatalSource = file('fatal-driver-src/org/h2/Driver.java')
                    assert fatalSource.parentFile.mkdirs()
                    fatalSource.text = 'package org.h2; public final class Driver implements java.sql.Driver {' +
                        'public Driver() { throw new InternalError("synthetic-fatal-constructor"); }' +
                        'public java.sql.Connection connect(String u, java.util.Properties p) { return null; }' +
                        'public boolean acceptsURL(String u) { return false; }' +
                        'public java.sql.DriverPropertyInfo[] getPropertyInfo(String u, java.util.Properties p) { return null; }' +
                        'public int getMajorVersion() { return 0; }' +
                        'public int getMinorVersion() { return 0; }' +
                        'public boolean jdbcCompliant() { return false; }' +
                        'public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }' +
                        '}'
                    def compiler = javax.tools.ToolProvider.systemJavaCompiler
                    assert compiler != null && compiler.run(null, null, null, '-d', fatalClasses.absolutePath, fatalSource.absolutePath) == 0
                    task.driverClasspath.setFrom(fatalClasses)
                    try { task.verifySnapshot(); assert false : 'Fatal constructor swallowed' }
                    catch (InternalError fatalConstructor) { assert fatalConstructor.message == 'synthetic-fatal-constructor' }
                    task.connectionString.set('jdbc:h2:file:secret-unique-do-not-log')
                    try { task.verifySnapshot(); assert false : 'Unsafe connection accepted' }
                    catch (IllegalArgumentException failure) {
                        assert failure.message == 'Invalid security snapshot: CONFIGURATION'
                        assert failure.cause == null && failure.stackTrace.length == 0 && failure.suppressed.length == 0
                    }
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
                def selected = [':', ':platform', ':enforcer', ':profiler', ':portals:runtime', ':portals:worker', ':portals:codegen']
                def excludedRoots = [':demos', ':integrations', ':tools']
                def actualSdk = root.allprojects.findAll { child ->
                    !excludedRoots.any { excluded -> child.path == excluded || child.path.startsWith(excluded + ':') }
                }.collect { it.path } as Set
                assert actualSdk == ((selected + [':portals']) as Set) : 'Default SDK project set changed; review scan scope'
                def parent = root.project(':portals')
                assert !parent.plugins.hasPlugin('java')
                assert parent.configurations.findByName('compileClasspath') == null
                assert parent.configurations.findByName('runtimeClasspath') == null
                def lifecycle = root.tasks.named('dependencyCheckSdk').get()
                def expectedTasks = selected.collect { (it == ':' ? '' : it) + ':dependencyCheckAnalyze' } as Set
                assert (lifecycle.dependsOn.flatten() as Set) == expectedTasks
                def reportDirs = [] as Set
                selected.each { path ->
                    def owner = root.project(path)
                    def task = owner.tasks.named('dependencyCheckAnalyze').get()
                    assert !task.autoUpdate.get()
                    assert task.dependsOn.flatten().any { dependency ->
                        dependency instanceof CharSequence ? dependency.toString() == ':verifySecuritySnapshot' : dependency.name == 'verifySecuritySnapshot'
                    }
                    assert (task.scanConfigurations.get() as Set) == (['compileClasspath', 'runtimeClasspath'] as Set)
                    assert task.failBuildOnCVSS.get() == 7.0f && task.failOnError.get()
                    assert task.suppressionFile.get() == extension.suppressionFile.get()
                    assert task.data.directory.get() == extension.data.directory.get()
                    assert !task.analyzers.ossIndex.enabled.get()
                    def yarnFlag = task.analyzers.nodeAudit.yarnEnabled.orNull
                    assert path == ':portals:codegen' ? yarnFlag == false : yarnFlag != false
                    assert task.analyzers.nodeAudit.enabled.orNull != false
                    assert task.analyzers.retirejs.enabled.orNull != false
                    assert task.analyzers.jarEnabled.orNull != false
                    assert reportDirs.add(task.outputDir.get().asFile.canonicalPath)
                }
            }
            """.formatted(repository.toRealPath().toUri().toASCIIString()));
        var runner = GradleRunner.create().withProjectDir(repository.toFile())
            .withArguments("dependencyCheckAnalyze", "--dry-run", "--offline", "--no-configuration-cache",
                "-Pmazewall.securitySnapshotScan=true", "--init-script", init.toString(), "--gradle-user-home", gradleUserHome());
        String output = runner.build().getOutput();
        if (!output.contains(":verifySecuritySnapshot") || !output.contains(":dependencyCheckAnalyze")) {
            throw new AssertionError("Scan must schedule the verifier before the actual analyzer: " + output);
        }
        String sdkGraph = GradleRunner.create().withProjectDir(repository.toFile())
            .withArguments("dependencyCheckSdk", "--dry-run", "--offline", "--no-parallel", "--max-workers=1", "--no-configuration-cache",
                "-Pmazewall.securitySnapshotScan=true", "--init-script", init.toString(), "--gradle-user-home", gradleUserHome())
            .build().getOutput();
        var scheduled = sdkGraph.lines().map(String::trim).filter(line -> line.startsWith(":") && line.endsWith(" SKIPPED"))
            .map(line -> line.substring(0, line.indexOf(' '))).toList();
        var expectedOrder = java.util.List.of(":verifySecuritySnapshot", ":dependencyCheckAnalyze", ":platform:dependencyCheckAnalyze",
            ":enforcer:dependencyCheckAnalyze", ":profiler:dependencyCheckAnalyze", ":portals:runtime:dependencyCheckAnalyze",
            ":portals:worker:dependencyCheckAnalyze", ":portals:codegen:dependencyCheckAnalyze", ":dependencyCheckSdk");
        int previous = -1;
        for (String expected : expectedOrder) {
            int position = scheduled.indexOf(expected);
            if (position <= previous) throw new AssertionError("SDK scan graph is missing or reordered " + expected + ": " + sdkGraph);
            previous = position;
        }
        if (scheduled.stream().anyMatch(path -> path.contains("dependencyCheckAggregate") || path.startsWith(":demos:")
            || path.startsWith(":integrations:") || path.startsWith(":tools:"))) {
            throw new AssertionError("SDK graph includes an excluded task: " + sdkGraph);
        }
        Path ordinaryInit = scratch.resolve("ordinary-yarn-contract.gradle");
        Files.writeString(ordinaryInit, """
            gradle.projectsEvaluated {
                if (gradle.rootProject.projectDir.canonicalFile != new File(new URI('%s')).canonicalFile) return
                def root = gradle.rootProject
                assert root.tasks.named('dependencyCheckAnalyze').get().analyzers.nodeAudit.yarnEnabled.orNull != false
                assert !root.project(':portals:codegen').plugins.hasPlugin('org.owasp.dependencycheck')
            }
            """.formatted(repository.toRealPath().toUri().toASCIIString()));
        GradleRunner.create().withProjectDir(repository.toFile())
            .withArguments("help", "--offline", "--no-configuration-cache", "--init-script", ordinaryInit.toString(),
                "--gradle-user-home", gradleUserHome()).build();
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
                    [gradle.rootProject, gradle.rootProject.project(':profiler')].each { owner ->
                        owner.tasks.named('dependencyCheckAnalyze').get().nvd.endpoint.set('http://127.0.0.1:%d/')
                    }
                }
                """.formatted(repository.toRealPath().toUri().toASCIIString(), listener.getLocalPort()));
            for (String task : new String[] {":dependencyCheckAnalyze", ":profiler:dependencyCheckAnalyze"}) {
                var result = GradleRunner.create().withProjectDir(repository.toFile())
                    .withEnvironment(java.util.Map.of("CI", "true", "GRADLE_USER_HOME", scratch.toString(),
                        "JAVA_HOME", System.getProperty("java.home"), "PATH", "/usr/bin:/bin", "HOME", scratch.toString()))
                    .withArguments(task, "--offline", "--no-configuration-cache",
                        "-Pmazewall.securitySnapshotScan=true", "--init-script", init.toString(),
                        "--gradle-user-home", gradleUserHome()).buildAndFail();
                String output = result.getOutput();
                if (task.equals(":profiler:dependencyCheckAnalyze") &&
                    (output.contains("you can only specify one of scanProjects or skipProjects") ||
                     !output.contains("Unable to access the local database.") ||
                     !output.contains("Verifying dependencies for project profiler"))) {
                    throw new AssertionError("Owner scan did not reach the expected incomplete test database failure: " + output);
                }
                var verified = result.task(":verifySecuritySnapshot");
                var analyzed = result.task(task);
                if (verified == null || verified.getOutcome() != org.gradle.testkit.runner.TaskOutcome.SUCCESS
                    || analyzed == null || analyzed.getOutcome() != org.gradle.testkit.runner.TaskOutcome.FAILED) {
                    throw new AssertionError("Keyless " + task + " did not execute after verification: " + result.getOutput());
                }
            }
            Path missing = Files.createDirectory(scratch.resolve("missing-home"));
            var missingResult = GradleRunner.create().withProjectDir(repository.toFile())
                .withEnvironment(java.util.Map.of("CI", "true", "GRADLE_USER_HOME", missing.toString(),
                    "JAVA_HOME", System.getProperty("java.home"), "PATH", "/usr/bin:/bin", "HOME", missing.toString()))
                .withArguments(":profiler:dependencyCheckAnalyze", "--offline", "--no-configuration-cache",
                    "-Pmazewall.securitySnapshotScan=true", "--init-script", init.toString(),
                    "--gradle-user-home", gradleUserHome()).buildAndFail();
            var missingVerification = missingResult.task(":verifySecuritySnapshot");
            var missingOwner = missingResult.task(":profiler:dependencyCheckAnalyze");
            if (missingVerification == null || missingVerification.getOutcome() != org.gradle.testkit.runner.TaskOutcome.FAILED
                || (missingOwner != null && missingOwner.getOutcome() != org.gradle.testkit.runner.TaskOutcome.SKIPPED)) {
                throw new AssertionError("Missing snapshot must prevent owner analysis: " + missingResult.getOutput());
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
