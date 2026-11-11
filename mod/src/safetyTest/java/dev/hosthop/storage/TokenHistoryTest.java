package dev.hosthop.storage;

import java.nio.file.Files;
import java.time.Instant;

public final class TokenHistoryTest {
    public static void main(String[] arguments) throws Exception {
        var dir = Files.createTempDirectory("hosthop-token-history");
        try {
            Instant now = Instant.parse("2026-11-18T12:00:00Z");
            long issued = Instant.parse("2026-11-11T00:00:00Z").getEpochSecond();
            if (!"7.5 days".equals(TokenHistory.age(Long.toString(issued), now))) throw new AssertionError("age");
            if (TokenHistory.age(null, now) != null || TokenHistory.age("abc", now) != null
                    || TokenHistory.age("0", now) != null || TokenHistory.age(Long.toString(now.getEpochSecond() + 10), now) != null)
                throw new AssertionError("unknown ages must stay unknown");
            TokenHistory.record(dir, "LINKED test\nINJECTED second line");
            var text = Files.readString(dir.resolve(TokenHistory.FILE));
            if (text.lines().count() != 1 || !text.contains("LINKED test INJECTED second line")) throw new AssertionError("one event per line");
            TokenHistory.record(null, "ignored"); TokenHistory.record(dir, null);
            Files.writeString(dir.resolve(TokenHistory.FILE), "x".repeat(70 * 1024));
            TokenHistory.record(dir, "after rotation");
            if (!Files.exists(dir.resolve(TokenHistory.FILE + ".old")) || Files.size(dir.resolve(TokenHistory.FILE)) > 1024)
                throw new AssertionError("log must rotate");
            System.out.println("Token history checks passed");
        } finally {
            try (var files = Files.list(dir)) { for (var f : (Iterable<java.nio.file.Path>) files::iterator) Files.deleteIfExists(f); }
            Files.deleteIfExists(dir);
        }
    }
}
