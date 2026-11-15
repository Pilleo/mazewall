package io.mazewall.build;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Driver;
import java.time.Instant;
import java.util.Properties;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Always validates snapshot freshness at execution time. */
@DisableCachingByDefault(because = "Freshness depends on the execution clock")
public abstract class VerifySecuritySnapshotTask extends DefaultTask {
    public VerifySecuritySnapshotTask() {
        getOutputs().upToDateWhen(task -> false);
    }

    @Internal public abstract Property<String> getDataDirectory();
    @Internal public abstract Property<String> getUsername();
    @Internal public abstract Property<String> getPassword();
    @Internal public abstract Property<String> getConnectionString();
    @Internal public abstract Property<String> getDriverName();
    @Classpath public abstract ConfigurableFileCollection getDriverClasspath();

    @TaskAction public void verifySnapshot() {
        try {
            if (!getConnectionString().get().equals("jdbc:h2:file:%s;AUTOCOMMIT=ON;CACHE_SIZE=65536;RETENTION_TIME=1000;MAX_COMPACT_TIME=10000;")
                || !getDriverName().get().equals("org.h2.Driver")) throw new IllegalArgumentException();
            var urls = getDriverClasspath().getFiles().stream().map(file -> {
                try { return file.toURI().toURL(); }
                catch (java.net.MalformedURLException failure) { throw new IllegalArgumentException(); }
            }).toArray(java.net.URL[]::new);
            try (var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
                Driver driver = (Driver) Class.forName("org.h2.Driver", true, loader).getDeclaredConstructor().newInstance();
                Properties credentials = new Properties();
                credentials.setProperty("user", getUsername().get());
                credentials.setProperty("password", getPassword().get());
                SecuritySnapshotVerifier.verify(Path.of(getDataDirectory().get()), driver, credentials, Instant.now());
            }
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid security snapshot");
        }
    }
}
