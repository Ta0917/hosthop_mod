package dev.hosthop.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import dev.hosthop.lease.PendingPublicationJournal;
import dev.hosthop.lease.PublicationRecord;

/** Recovery only for a locally locked session recorded before server launch. */
public final class PendingHostRecovery {
    @FunctionalInterface public interface Abort {
        void run(String world, String principal, String lease, long epoch, long generation) throws IOException, InterruptedException;
    }
    @FunctionalInterface public interface Cancel {
        void run(String world, String principal, String lease) throws IOException, InterruptedException;
    }
    private PendingHostRecovery() {}
    public record Committed(Path world, Path journal, PublicationRecord record) {}
    public record Stopped(Path world, Path session, String lease, long epoch, long generation) {}

    /** A stopped marker is written only after the server and relay have fully stopped. */
    public static Stopped findStopped(Map<String, String> evidence, String worldId, String principal, Path gameDirectory) throws IOException {
        return findLocal(evidence, worldId, principal, gameDirectory, false);
    }

    /** For an isolated local copy only; a crash does not prove a consistent server save. */
    public static Stopped findForExport(Map<String, String> evidence, String worldId, String principal, Path gameDirectory) throws IOException {
        return findLocal(evidence, worldId, principal, gameDirectory, true);
    }

    /** Explicitly approved unclean data remains distinct from a verified orderly stop. */
    public static Stopped findConfirmedUnclean(Map<String, String> evidence, String worldId, String principal, Path gameDirectory) throws IOException {
        if (evidence == null || !"unclean-confirmed".equals(evidence.get("stage"))) return null;
        return findLocal(evidence, worldId, principal, gameDirectory, true);
    }

    private static Stopped findLocal(Map<String, String> evidence, String worldId, String principal, Path gameDirectory, boolean allowUnclean) throws IOException {
        if (evidence == null || !("stopped".equals(evidence.get("stage")) || allowUnclean && ("starting".equals(evidence.get("stage"))
                || "unclean-confirmed".equals(evidence.get("stage")))) || !worldId.equals(evidence.get("world"))
                || !principal.equals(evidence.get("principal"))) return null;
        String id = evidence.get("lease"), level = evidence.get("level");
        long epoch, generation;
        try {
            if (id == null || !UUID.fromString(id).toString().equals(id) || !id.equals(evidence.get("session"))
                    || !("hosthop_" + id.replace("-", "")).equals(level)) return null;
            epoch = Long.parseLong(evidence.get("epoch")); generation = Long.parseLong(evidence.get("generation"));
            if (epoch < 1 || generation < 0) return null;
        } catch (RuntimeException invalid) { return null; }
        Path game = gameDirectory.toRealPath();
        Path session = game.resolve("hosthop").resolve("sessions").resolve(id);
        Path world = game.resolve("saves").resolve(level);
        if (!Files.isDirectory(session, LinkOption.NOFOLLOW_LINKS) || !session.toRealPath().startsWith(game)
                || !Files.isDirectory(world, LinkOption.NOFOLLOW_LINKS) || !world.toRealPath().startsWith(game)) return null;
        return new Stopped(world, session, id, epoch, generation);
    }

    /** Validate local evidence before attempting an authenticated release. */
    public static Committed findCommitted(Map<String, String> evidence, String worldId, String principal, Path gameDirectory) throws IOException {
        if (evidence == null || !("starting".equals(evidence.get("stage")) || "stopped".equals(evidence.get("stage"))
                || "unclean-confirmed".equals(evidence.get("stage"))) || !worldId.equals(evidence.get("world"))
                || !principal.equals(evidence.get("principal"))) return null;
        String id = evidence.get("lease"), level = evidence.get("level");
        long epoch, generation;
        try {
            if (id == null || !UUID.fromString(id).toString().equals(id) || !id.equals(evidence.get("session"))
                    || !("hosthop_" + id.replace("-", "")).equals(level)) return null;
            epoch = Long.parseLong(evidence.get("epoch")); generation = Long.parseLong(evidence.get("generation"));
        } catch (RuntimeException invalid) { return null; }
        Path game = gameDirectory.toRealPath();
        Path session = game.resolve("hosthop").resolve("sessions").resolve(id);
        Path world = game.resolve("saves").resolve(level);
        Path journal = session.resolve("publication.dat");
        if (!Files.isDirectory(session, LinkOption.NOFOLLOW_LINKS) || !session.toRealPath().startsWith(game)
                || !Files.isDirectory(world, LinkOption.NOFOLLOW_LINKS) || !world.toRealPath().startsWith(game)
                || !Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)) return null;
        var record = PendingPublicationJournal.read(journal);
        if (!record.worldId().equals(worldId) || !record.owner().equals(principal) || !record.leaseId().equals(id)
                || record.epoch() != epoch || record.previousGeneration() != generation) return null;
        return new Committed(world, journal, record);
    }

    /** Caller must hold the same exclusive process lock used by every local host. */
    public static boolean abortUnstarted(Map<String, String> pending, String world, String principal, Abort abort, Cancel cancel)
            throws IOException, InterruptedException {
        if (pending == null || abort == null || cancel == null || !("preparing".equals(pending.get("stage")) || "acquiring".equals(pending.get("stage")))
                || !world.equals(pending.get("world")) || !principal.equals(pending.get("principal"))) return false;
        String lease = pending.get("lease");
        long epoch, generation;
        try {
            if (lease == null || !UUID.fromString(lease).toString().equals(lease)
                    || !lease.equals(pending.get("session"))
                    || !("hosthop_" + lease.replace("-", "")).equals(pending.get("level"))) return false;
            if ("acquiring".equals(pending.get("stage"))) {
                cancel.run(world, principal, lease);
                return true;
            }
            epoch = Long.parseLong(pending.get("epoch"));
            generation = Long.parseLong(pending.get("generation"));
        } catch (RuntimeException invalid) { return false; }
        if (epoch < 1 || generation < 0) return false;
        abort.run(world, principal, lease, epoch, generation);
        return true;
    }
}
