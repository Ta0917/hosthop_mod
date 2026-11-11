package dev.hosthop.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * A plain-text diary of when Drive credentials were issued and when they failed, so an expiry error
 * can be explained later. It never contains tokens, account names or folder identifiers.
 */
public final class TokenHistory {
    public static final String FILE = "drive-token-history.log";
    private static final long MAX_BYTES = 64 * 1024;
    private TokenHistory() {}

    public static String format(Instant instant) {
        return ZonedDateTime.ofInstant(instant, ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /** "3.2 days" style age, or null when the issue time is unknown. */
    public static String age(String issuedEpochSeconds, Instant now) {
        if (issuedEpochSeconds == null) return null;
        try {
            long issued = Long.parseLong(issuedEpochSeconds);
            if (issued <= 0 || issued > now.getEpochSecond()) return null;
            double days = Duration.between(Instant.ofEpochSecond(issued), now).toSeconds() / 86400.0;
            return String.format(java.util.Locale.ROOT, "%.1f days", days);
        } catch (NumberFormatException invalid) { return null; }
    }

    /** Best effort: diagnostics must never break authorization. */
    public static void record(Path directory, String event) {
        if (directory == null || event == null) return;
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(FILE);
            if (Files.isSymbolicLink(file)) return;
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) > MAX_BYTES)
                Files.move(file, directory.resolve(FILE + ".old"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            String line = format(Instant.now()) + " " + event.replace('\n', ' ').replace('\r', ' ') + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) { }
    }
}
