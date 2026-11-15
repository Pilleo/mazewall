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
import java.time.format.DateTimeParseException;
import java.util.Properties;

/** Checks the scanner's actual local database without modifying it. */
public final class SecuritySnapshotVerifier {
    private SecuritySnapshotVerifier() {}

    enum Reason { PATH_OWNERSHIP, CONFIGURATION, DATABASE, SCHEMA, METADATA, TIME_ORDER, STALE, EMPTY_CVES }

    static final class SnapshotFailure extends IllegalArgumentException {
        private final Reason reason;
        private final Instant checked;
        private final Instant modified;
        private final long count;

        private SnapshotFailure(Reason reason, Instant checked, Instant modified, long count) {
            super(message(reason, checked, modified, count));
            this.reason = reason;
            this.checked = checked;
            this.modified = modified;
            this.count = count;
        }

        private static String message(Reason reason, Instant checked, Instant modified, long count) {
            String result = "Invalid security snapshot: " + reason.name();
            if (reason == Reason.STALE) {
                result += " schema=5.6 checked=" + checked + " modified=" + modified + " cveCount=" + count;
            }
            return result;
        }

        SnapshotFailure fresh() { return new SnapshotFailure(reason, checked, modified, count); }
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }

    static SnapshotFailure failure(Reason reason) {
        return new SnapshotFailure(reason, null, null, 0);
    }

    public static void verify(Path directory, Driver driver, Properties credentials, Instant now) {
        try {
            validate(directory, driver, credentials, now);
        } catch (SnapshotFailure failure) {
            throw failure.fresh();
        } catch (Exception | LinkageError failure) {
            throw failure(Reason.DATABASE);
        }
    }

    private static void validate(Path directory, Driver driver, Properties credentials, Instant now) throws Exception {
        if (!directory.isAbsolute() || !directory.normalize().equals(directory)
            || directory.toString().matches(".*[;\\\\\\r\\n].*")) throw failure(Reason.PATH_OWNERSHIP);
        Path current = directory.getRoot();
        for (Path part : directory) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw failure(Reason.PATH_OWNERSHIP);
        }
        Path file = directory.resolve("odc.mv.db");
        long uid = new UnixSystem().getUid();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
            || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
            || Files.size(file) == 0
            || ((Number) Files.getAttribute(directory, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue() != uid
            || ((Number) Files.getAttribute(file, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue() != uid) {
            throw failure(Reason.PATH_OWNERSHIP);
        }
        String url = "jdbc:h2:file:" + directory.resolve("odc")
            + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;FILE_LOCK=NO;TRACE_LEVEL_FILE=0";
        try (Connection connection = driver.connect(url, credentials)) {
            if (connection == null) throw failure(Reason.DATABASE);
            String version = property(connection, "version");
            if (!"5.6".equals(version)) throw failure(Reason.SCHEMA);
            Instant checked = timestamp(property(connection, "nvd.api.last.checked"));
            Instant modified = timestamp(property(connection, "nvd.api.last.modified"));
            if (modified.isAfter(checked) || checked.isAfter(now)) throw failure(Reason.TIME_ORDER);
            long count;
            try (var query = connection.createStatement();
                 ResultSet result = query.executeQuery("SELECT COUNT(*) FROM vulnerability WHERE cve LIKE 'CVE-%'")) {
                if (!result.next()) throw failure(Reason.DATABASE);
                count = result.getLong(1);
                if (result.next()) throw failure(Reason.DATABASE);
            }
            if (count <= 0) throw failure(Reason.EMPTY_CVES);
            if (Duration.between(checked, now).compareTo(Duration.ofHours(4)) > 0) {
                throw new SnapshotFailure(Reason.STALE, checked, modified, count);
            }
        }
    }

    private static Instant timestamp(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException failure) {
            throw failure(Reason.METADATA);
        }
    }

    private static String property(Connection connection, String key) throws Exception {
        try (var query = connection.prepareStatement("SELECT `value` FROM properties WHERE id = ?")) {
            query.setString(1, key);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw failure(Reason.METADATA);
                String value = result.getString(1);
                if (value == null || result.next()) throw failure(Reason.METADATA);
                return value;
            }
        }
    }
}
