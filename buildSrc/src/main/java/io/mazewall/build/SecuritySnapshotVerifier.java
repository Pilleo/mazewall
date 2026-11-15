package io.mazewall.build;

import com.sun.security.auth.module.UnixSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Properties;

/** Checks the scanner's actual local database without modifying it. */
public final class SecuritySnapshotVerifier {
    private SecuritySnapshotVerifier() {}

    public static void verify(Path directory, Driver driver, Properties credentials, Instant now) {
        try {
            validate(directory, driver, credentials, now);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid security snapshot");
        }
    }

    private static void validate(Path directory, Driver driver, Properties credentials, Instant now) throws Exception {
        if (!directory.isAbsolute() || !directory.normalize().equals(directory)
            || directory.toString().matches(".*[;\\\\\\r\\n].*")) throw new IllegalArgumentException();
        Path current = directory.getRoot();
        for (Path part : directory) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException();
        }
        Path file = directory.resolve("odc.mv.db");
        long uid = new UnixSystem().getUid();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
            || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
            || Files.size(file) == 0
            || ((Number) Files.getAttribute(directory, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue() != uid
            || ((Number) Files.getAttribute(file, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue() != uid) {
            throw new IllegalArgumentException();
        }
        String url = "jdbc:h2:file:" + directory.resolve("odc")
            + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;FILE_LOCK=NO;TRACE_LEVEL_FILE=0";
        try (Connection connection = driver.connect(url, credentials)) {
            if (connection == null) throw new IllegalArgumentException();
            String version = property(connection, "version");
            Instant checked = OffsetDateTime.parse(property(connection, "nvd.api.last.checked")).toInstant();
            Instant modified = OffsetDateTime.parse(property(connection, "nvd.api.last.modified")).toInstant();
            if (!"5.6".equals(version) || modified.isAfter(checked) || checked.isAfter(now)
                || Duration.between(checked, now).compareTo(Duration.ofHours(4)) > 0) {
                throw new IllegalArgumentException();
            }
            try (var query = connection.createStatement();
                 ResultSet result = query.executeQuery("SELECT COUNT(*) FROM vulnerability WHERE cve LIKE 'CVE-%'")) {
                if (!result.next() || result.getLong(1) <= 0 || result.next()) throw new IllegalArgumentException();
            }
        }
    }

    private static String property(Connection connection, String key) throws Exception {
        try (var query = connection.prepareStatement("SELECT `value` FROM properties WHERE id = ?")) {
            query.setString(1, key);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException();
                String value = result.getString(1);
                if (value == null || result.next()) throw new IllegalArgumentException();
                return value;
            }
        }
    }
}
