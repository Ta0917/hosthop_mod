package dev.hosthop.world;

import net.minecraft.server.MinecraftServer;

/** Tracks a normal return from stopServer for the one active shared-world server. */
public final class ServerStopSignal {
    private static MinecraftServer watched;
    private static boolean completed;
    private ServerStopSignal() {}
    public static synchronized void watch(MinecraftServer server) { watched = server; completed = false; }
    public static synchronized void completed(MinecraftServer server) { if (server == watched) completed = true; }
    public static synchronized boolean completedFor(MinecraftServer server) { return server == watched && completed; }
    public static synchronized void clear(MinecraftServer server) { if (server == watched) { watched = null; completed = false; } }
}
