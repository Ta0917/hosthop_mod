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
        check(PendingHostRecovery.abortUnstarted(pending, "world", "owner", abort));
        for (String stage : new String[]{"acquiring", "starting", "hosting"}) {
            pending.put("stage", stage);
            check(!PendingHostRecovery.abortUnstarted(pending, "world", "owner", abort));
        }
        pending.put("stage", "preparing");
        check(!PendingHostRecovery.abortUnstarted(pending, "other", "owner", abort));
        pending.put("generation", "-1");
        check(!PendingHostRecovery.abortUnstarted(pending, "world", "owner", abort));
        pending.put("generation", "2");
        try {
            PendingHostRecovery.abortUnstarted(pending, "world", "owner", (w, o, l, e, g) -> { throw new IOException("offline"); });
            throw new AssertionError("Failed abort must not be treated as recovered");
        } catch (IOException expected) { }
        check(calls.get() == 1);
        System.out.println("Pending pre-launch recovery checks passed");
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
