package dev.hosthop.world;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/** Recovery only for a locally locked session recorded before server launch. */
public final class PendingHostRecovery {
    @FunctionalInterface public interface Abort {
        void run(String world, String principal, String lease, long epoch, long generation) throws IOException, InterruptedException;
    }
    private PendingHostRecovery() {}

    /** Caller must hold the same exclusive process lock used by every local host. */
    public static boolean abortUnstarted(Map<String, String> pending, String world, String principal, Abort abort)
            throws IOException, InterruptedException {
        if (pending == null || abort == null || !"preparing".equals(pending.get("stage"))
                || !world.equals(pending.get("world")) || !principal.equals(pending.get("principal"))) return false;
        String lease = pending.get("lease");
        long epoch, generation;
        try {
            if (lease == null || !UUID.fromString(lease).toString().equals(lease)
                    || !lease.equals(pending.get("session"))
                    || !("hosthop_" + lease.replace("-", "")).equals(pending.get("level"))) return false;
            epoch = Long.parseLong(pending.get("epoch"));
            generation = Long.parseLong(pending.get("generation"));
        } catch (RuntimeException invalid) { return false; }
        if (epoch < 1 || generation < 0) return false;
        abort.run(world, principal, lease, epoch, generation);
        return true;
    }
}
