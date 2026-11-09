package dev.hosthop.world;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Keeps the existing JVM alive only while a verified host shutdown is being saved. */
public final class SaveKeepAlive implements AutoCloseable {
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private final CountDownLatch finished = new CountDownLatch(1);

    public SaveKeepAlive() {
        ACTIVE.incrementAndGet();
        var thread = new Thread(() -> {
            try { finished.await(2, TimeUnit.HOURS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { ACTIVE.decrementAndGet(); }
        }, "hosthop-save-keepalive");
        thread.setDaemon(false);
        try { thread.start(); } catch (RuntimeException | Error failure) { ACTIVE.decrementAndGet(); throw failure; }
    }

    public static boolean active() { return ACTIVE.get() > 0; }
    public static boolean suppressWatchdog(String callsite, boolean hostActive) {
        return ("post-main".equals(callsite) && active()) || ("window close callback".equals(callsite) && hostActive);
    }
    @Override public void close() { finished.countDown(); }
}
