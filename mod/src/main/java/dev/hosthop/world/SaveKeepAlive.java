package dev.hosthop.world;

import java.util.concurrent.CountDownLatch;

/** Keeps the existing JVM alive only while a verified host shutdown is being saved. */
public final class SaveKeepAlive implements AutoCloseable {
    private final CountDownLatch finished = new CountDownLatch(1);

    public SaveKeepAlive() {
        var thread = new Thread(() -> {
            try { finished.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, "hosthop-save-keepalive");
        thread.setDaemon(false);
        thread.start();
    }

    @Override public void close() { finished.countDown(); }
}
