package dev.hosthop.stopprobe;

import dev.hosthop.lease.ConservativeLeaseDeadline;
import dev.hosthop.lease.LocalHostGate;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Only for an opt-in, empty GameTestServer. Uses a synthetic lease and clock. */
@EventBusSubscriber(modid = "hosthop")
public final class HostStopProbe {
    private static MinecraftServer server;
    private static LocalHostGate gate;
    private static LocalHostGate.Attempt attempt;
    private static int entered;
    private static int packetCalls;
    private static boolean stoppedBeforePackets;
    private static boolean canceledWaitingTasks;
    private static volatile boolean queuedTaskRan;

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("hosthop.hostStopProbe") ||
                !event.getServer().getClass().getName().equals("net.minecraft.gametest.framework.GameTestServer")) return;
        server = event.getServer();
        gate = new LocalHostGate();
        attempt = gate.beginAcquire();
        var deadline = ConservativeLeaseDeadline.fromGrant(0, 0, 100, 130, 2);
        if (!gate.acceptGrant(attempt, 1, 0, deadline, 0, 0) ||
                !gate.snapshotVerified(attempt, 0, 0, 0) || !gate.beginHosting(attempt, 0, 0)) {
            throw new IllegalStateException("Failed to set up synthetic lease");
        }
        System.out.println("HOST_STOP_PROBE armed with synthetic lease");
    }

    public static boolean beforePackets(MinecraftServer candidate) {
        if (candidate != server || gate == null) return false;
        entered++;
        long nanos = entered >= 4 ? 27_000_000_000L : 0L;
        long millis = entered >= 4 ? 27_000L : 0L;
        if (gate.mayContinueHosting(attempt, nanos, millis)) return false;
        stoppedBeforePackets = true;
        Thread producer = new Thread(() -> candidate.execute(() -> {
            queuedTaskRan = true;
            System.out.println("HOST_STOP_PROBE queued task ran during shutdown");
        }), "hosthop-stop-probe-producer");
        producer.start();
        try {
            producer.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Could not queue shutdown probe", e);
        }
        if (producer.isAlive()) throw new IllegalStateException("Shutdown probe did not queue");
        candidate.halt(false);
        System.out.println("HOST_STOP_PROBE stopping before packet processing");
        return true;
    }

    public static void packetProcessingReached(MinecraftServer candidate) {
        if (candidate == server && gate != null) packetCalls++;
    }

    public static boolean beforeWaiting(MinecraftServer candidate) {
        if (candidate != server || !stoppedBeforePackets || canceledWaitingTasks) return false;
        canceledWaitingTasks = true;
        System.out.println("HOST_STOP_PROBE skipped waiting tasks after stop");
        return true;
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        if (event.getServer() != server || gate == null) return;
        boolean expected = stoppedBeforePackets && canceledWaitingTasks && entered == 4 && packetCalls == 3 && gate.confirmStopped(attempt);
        System.out.println("HOST_STOP_PROBE server stopped and ordering verified=" + expected);
        System.out.println("HOST_STOP_PROBE counts=" + entered + "/" + packetCalls + ", skipped waiting=" + canceledWaitingTasks);
        System.out.println("HOST_STOP_PROBE queued task ran before shutdown completed=" + queuedTaskRan);
    }
}
