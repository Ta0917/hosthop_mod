package dev.hosthop.world;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.UUID;

public final class SessionCleanupTest {
    public static void main(String[] args) throws Exception {
        Path game = Files.createTempDirectory("hosthop-cleanup-test-").toRealPath();
        Path sessions = Files.createDirectories(game.resolve("hosthop").resolve("sessions"));
        Path saves = Files.createDirectories(game.resolve("saves"));
        Instant now = Instant.parse("2026-11-11T12:00:00Z");

        String newest = session(sessions, saves, now.minusSeconds(60), true);
        String second = session(sessions, saves, now.minusSeconds(120), true);
        String old = session(sessions, saves, now.minusSeconds(180), true);
        String unreleased = session(sessions, saves, now.minusSeconds(240), false);
        String locked = session(sessions, saves, now.minusSeconds(300), true);
        Path user = Files.createDirectories(saves.resolve("My World"));
        Path freshTemp = Files.createDirectories(sessions.resolve("authorization-fresh"));
        Path oldTemp = Files.createDirectories(sessions.resolve("authorization-old"));
        Files.setLastModifiedTime(oldTemp, FileTime.from(now.minusSeconds(7200)));
        Files.setLastModifiedTime(freshTemp, FileTime.from(now.minusSeconds(60)));

        try (var channel = FileChannel.open(world(saves, locked).resolve("session.lock"), StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            int removed = SessionCleanup.removeReleased(game, 2, now);
            check(removed == 2, "old released session and old temporary folder removed");
        }
        check(exists(sessions, saves, newest) && exists(sessions, saves, second), "newest released sessions kept");
        check(!Files.exists(sessions.resolve(old)) && !Files.exists(world(saves, old)), "older released session removed");
        check(exists(sessions, saves, unreleased), "session without release marker kept");
        check(exists(sessions, saves, locked), "world in use kept");
        check(Files.isDirectory(user), "user world untouched");
        check(Files.isDirectory(freshTemp) && !Files.exists(oldTemp), "only old temporary folders removed");

        int again = SessionCleanup.removeReleased(game, 2, now);
        check(again == 1 && !Files.exists(sessions.resolve(locked)), "unlocked world removed on a later pass");
        try { SessionCleanup.removeReleased(game, 0, now); throw new AssertionError("keep=0 accepted"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("Session cleanup checks passed");
    }

    private static String session(Path sessions, Path saves, Instant released, boolean marked) throws Exception {
        String id = UUID.randomUUID().toString();
        Path session = Files.createDirectory(sessions.resolve(id));
        Files.write(session.resolve("final.zip"), new byte[]{1, 2, 3});
        Path world = Files.createDirectories(world(saves, id).resolve("region"));
        Files.write(world.resolve("r.0.0.mca"), new byte[]{4});
        Files.write(world(saves, id).resolve("session.lock"), new byte[0]);
        if (marked) {
            SessionCleanup.markReleased(session);
            Files.setLastModifiedTime(session.resolve(SessionCleanup.MARKER), FileTime.from(released));
        }
        return id;
    }
    private static Path world(Path saves, String id) { return saves.resolve("hosthop_" + id.replace("-", "")); }
    private static boolean exists(Path sessions, Path saves, String id) {
        return Files.isDirectory(sessions.resolve(id)) && Files.isDirectory(world(saves, id));
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
