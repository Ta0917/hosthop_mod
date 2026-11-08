package dev.hosthop.world;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class PendingHostRecoveryTest {
    public static void main(String[] args) throws Exception {
        String id = "4a075780-daa5-4f87-a733-d6a8c334b2a7";
        var pending = new HashMap<>(Map.of("world", "world", "principal", "owner", "lease", id, "session", id,
                "level", "hosthop_" + id.replace("-", ""), "stage", "preparing", "epoch", "4", "generation", "2"));
        var calls = new AtomicInteger();
        PendingHostRecovery.Abort abort = (world, owner, lease, epoch, generation) -> {
            check(world.equals("world") && owner.equals("owner") && lease.equals(id) && epoch == 4 && generation == 2);
            calls.incrementAndGet();
        };
        PendingHostRecovery.Cancel cancel = (world, owner, lease) -> { check(lease.equals(id)); calls.incrementAndGet(); };
        check(PendingHostRecovery.abortUnstarted(pending, "world", "owner", abort, cancel));
        pending.put("stage", "acquiring");
        check(PendingHostRecovery.abortUnstarted(pending, "world", "owner", abort, cancel));
        for (String stage : new String[]{"starting", "hosting"}) {
            pending.put("stage", stage);
            check(!PendingHostRecovery.abortUnstarted(pending, "world", "owner", abort, cancel));
        }
        pending.put("stage", "preparing");
        check(!PendingHostRecovery.abortUnstarted(pending, "other", "owner", abort, cancel));
        pending.put("generation", "-1");
        check(!PendingHostRecovery.abortUnstarted(pending, "world", "owner", abort, cancel));
        pending.put("generation", "2");
        try {
            PendingHostRecovery.abortUnstarted(pending, "world", "owner", (w, o, l, e, g) -> { throw new IOException("offline"); }, cancel);
            throw new AssertionError("Failed abort must not be treated as recovered");
        } catch (IOException expected) { }
        check(calls.get() == 2);
        var root = java.nio.file.Files.createTempDirectory("hosthop-pending-recovery-");
        try {
            var session = java.nio.file.Files.createDirectories(root.resolve("hosthop").resolve("sessions").resolve(id));
            var localWorld = java.nio.file.Files.createDirectories(root.resolve("saves").resolve(pending.get("level")));
            var journal = session.resolve("publication.dat");
            var record = new dev.hosthop.lease.PublicationRecord("world", "op", "owner", id, 4, 2, "digest", "file", 1, 3);
            dev.hosthop.lease.PendingPublicationJournal.create(journal, record);
            pending.put("stage", "starting");
            var found = PendingHostRecovery.findCommitted(pending, "world", "owner", root);
            check(found != null && found.world().equals(localWorld) && found.record().equals(record));
            check(PendingHostRecovery.findCommitted(pending, "world", "other", root) == null);
            pending.put("epoch", "5");
            check(PendingHostRecovery.findCommitted(pending, "world", "owner", root) == null);
            pending.put("epoch", "4");
            java.nio.file.Files.write(journal, new byte[] {1, 2, 3});
            try { PendingHostRecovery.findCommitted(pending, "world", "owner", root); throw new AssertionError("Corrupt evidence accepted"); }
            catch (IOException expected) { }
        } finally {
            try (var paths = java.nio.file.Files.walk(root)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(path);
            }
        }
        System.out.println("Pending pre-launch recovery checks passed");
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
