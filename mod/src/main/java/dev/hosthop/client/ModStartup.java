package dev.hosthop.client;

/** Public entry for the mod constructor; the client helpers stay package-private. */
public final class ModStartup {
    private ModStartup() {}
    public static void run() { ModSync.onStartup(); }
}
