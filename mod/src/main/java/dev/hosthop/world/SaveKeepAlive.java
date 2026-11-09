package dev.hosthop.world;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Keeps the existing JVM alive only while a verified host shutdown is being saved. */
public final class SaveKeepAlive implements AutoCloseable {
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final Object MONITOR = new Object();
    private static volatile boolean waitingForCompletion;
    private final CountDownLatch finished = new CountDownLatch(1);

    public SaveKeepAlive() {
        ACTIVE.incrementAndGet();
        var thread = new Thread(() -> {
            try { finished.await(2, TimeUnit.HOURS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { synchronized (MONITOR) { ACTIVE.decrementAndGet(); MONITOR.notifyAll(); } }
        }, "hosthop-save-keepalive");
        thread.setDaemon(false);
        try { thread.start(); } catch (RuntimeException | Error failure) { ACTIVE.decrementAndGet(); throw failure; }
    }

    public static boolean active() { return ACTIVE.get() > 0; }
    /** Keep the mod classloader open until asynchronous shutdown work is done. */
    public static void awaitCompletion() {
        synchronized (MONITOR) {
            if (!active()) return;
            waitingForCompletion = true;
            boolean interrupted = false;
            try {
                while (active()) {
                    try { MONITOR.wait(); }
                    catch (InterruptedException retry) { interrupted = true; }
                }
            } finally {
                waitingForCompletion = false;
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
    public static boolean waitingForCompletion() { return waitingForCompletion; }
    public static boolean suppressWatchdog(String callsite, boolean hostActive) {
        return ("post-main".equals(callsite) && active()) || ("window close callback".equals(callsite) && (hostActive || active()));
    }
    @Override public void close() { finished.countDown(); }
}
