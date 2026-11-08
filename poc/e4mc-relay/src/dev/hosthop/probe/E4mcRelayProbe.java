package dev.hosthop.probe;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

/** Opt-in experiment: creates an empty, temporary public e4mc tunnel. */
@EventBusSubscriber(modid = "hosthop")
public final class E4mcRelayProbe {
    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) throws Exception {
        if (!Boolean.getBoolean("hosthop.e4mcRelayProbe") ||
                !event.getServer().getClass().getName().equals("net.minecraft.gametest.framework.GameTestServer")) {
            return;
        }
        System.out.println("HOSTHOP_PROBE starting loopback listener for empty test world");
        event.getServer().getConnection().startTcpServerListener(InetAddress.getLoopbackAddress(), 0);
        Class<?> client = Class.forName("link.e4mc.E4mcClient");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        String state = "NO_SESSION";
        while (System.nanoTime() < deadline) {
            Object session = client.getField("session").get(null);
            if (session != null) {
                state = String.valueOf(session.getClass().getField("state").get(session));
                if (state.equals("STARTED") || state.equals("UNHEALTHY") || state.equals("STOPPED")) break;
            }
            Thread.sleep(100);
        }
        System.out.println("HOSTHOP_PROBE final session state=" + state);
        System.out.println("HOSTHOP_PROBE assignment captured=" + (CapturedEndpoint.address != null));
        if (!state.equals("STARTED") || CapturedEndpoint.address == null) {
            throw new IllegalStateException("Relay or assignment capture failed");
        }
        Object session = client.getField("session").get(null);
        session.getClass().getMethod("stop").invoke(session);
        long stopDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < stopDeadline &&
                !String.valueOf(session.getClass().getField("state").get(session)).equals("STOPPED")) {
            Thread.sleep(100);
        }
        state = String.valueOf(session.getClass().getField("state").get(session));
        System.out.println("HOSTHOP_PROBE after explicit stop=" + state + ", assignment absent=" + (CapturedEndpoint.address == null));
        if (!state.equals("STOPPED") || CapturedEndpoint.address != null) {
            throw new IllegalStateException("Session did not stop or assignment remained");
        }
    }
}
