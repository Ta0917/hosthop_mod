package dev.hosthop.relay;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone safety checks, run by the Gradle safetyCheck task. */
public final class SessionAddressRegistryTest {
    public static void main(String[] args) throws Exception {
        rejectsLateCallbacks();
        validatesCurrentAssignment();
        System.out.println("SessionAddressRegistry safety checks passed");
    }

    private static void rejectsLateCallbacks() throws Exception {
        var registry = new SessionAddressRegistry();
        var old = registry.begin();
        check(registry.publish(old, "old.example"), "initial assignment failed");
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread lateCallback = new Thread(() -> {
            try {
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("callback timed out");
                check(!registry.publish(old, "late.example"), "late assignment was accepted");
                check(!registry.end(old), "old close invalidated new session");
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        lateCallback.start();
        var current = registry.begin();
        check(registry.address().isEmpty(), "old address survived replacement");
        check(registry.publish(current, "new.example"), "new assignment failed");
        start.countDown();
        check(done.await(5, TimeUnit.SECONDS), "late callback did not finish");
        if (failure.get() != null) throw new AssertionError("late callback failed", failure.get());
        check(registry.address().orElseThrow().equals("new.example"), "new address changed");
        check(registry.end(current), "current close failed");
        check(registry.address().isEmpty(), "closed address remains");
        check(!registry.publish(current, "after-close.example"), "closed session accepted assignment");
    }

    private static void validatesCurrentAssignment() {
        var registry = new SessionAddressRegistry();
        var token = registry.begin();
        for (String invalid : new String[]{"", "  ", "bad\naddress", "x".repeat(256)}) {
            try {
                registry.publish(token, invalid);
                throw new AssertionError("invalid address accepted");
            } catch (IllegalArgumentException expected) {
                check(registry.address().isEmpty(), "invalid address was stored");
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
