package dev.hosthop.reconnect;

import java.util.concurrent.TimeUnit;

/** A single handoff wait stays bounded when connection attempts reopen the screen. */
public final class ReconnectWaitPolicy {
    private static final long LIMIT = TimeUnit.MINUTES.toNanos(30);
    private static final long SLOW_AFTER = TimeUnit.MINUTES.toNanos(5);
    private ReconnectWaitPolicy() {}
    public static boolean expired(long start, long now) { long elapsed = now - start; return elapsed < 0 || elapsed >= LIMIT; }
    public static int pollTicks(long start, long now) { return now - start < SLOW_AFTER ? 40 : 200; }
}
