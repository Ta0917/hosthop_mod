package dev.hosthop.lease;

public final class ConservativeLeaseDeadlineTest {
    public static void main(String[] args) {
        accountsForRequestDelayAndBoundaries();
        rejectsClockRollbackPermanently();
        failsClosedAfterSuspendOrDelay();
        rejectsInvalidServerIntervals();
        System.out.println("ConservativeLeaseDeadline safety checks passed");
    }

    private static void accountsForRequestDelayAndBoundaries() {
        var deadline = ConservativeLeaseDeadline.fromGrant(0, 0, 100, 130, 2);
        check(deadline.isUsable(5_000_000_000L, 5_000), "valid delayed response rejected");
        check(deadline.isUsable(26_999_000_000L, 26_999), "deadline ended too early");
        check(!deadline.isUsable(27_000_000_000L, 27_000), "boundary must be expired");
    }

    private static void rejectsClockRollbackPermanently() {
        var deadline = ConservativeLeaseDeadline.fromGrant(0, 1000, 100, 130, 2);
        check(deadline.isUsable(1_000_000_000L, 2000), "initial time rejected");
        check(!deadline.isUsable(2_000_000_000L, 1999), "wall-clock rollback accepted");
        check(!deadline.isUsable(3_000_000_000L, 3000), "expired deadline revived");
        var monotonic = ConservativeLeaseDeadline.fromGrant(100, 1000, 100, 130, 2);
        check(!monotonic.isUsable(99, 1001), "monotonic rollback accepted");
    }

    private static void failsClosedAfterSuspendOrDelay() {
        var suspended = ConservativeLeaseDeadline.fromGrant(0, 0, 100, 130, 2);
        check(!suspended.isUsable(100_000_000L, 30_000), "wall deadline ignored after suspend");
        var delayed = ConservativeLeaseDeadline.fromGrant(0, 0, 100, 130, 2);
        check(!delayed.isUsable(30_000_000_000L, 30_000), "expired response accepted");
        var tiny = ConservativeLeaseDeadline.fromGrant(0, 0, 100, 102, 2);
        check(!tiny.isUsable(0, 0), "zero safe interval accepted");
    }

    private static void rejectsInvalidServerIntervals() {
        expectFailure(() -> ConservativeLeaseDeadline.fromGrant(0, 0, 100, 99, 2));
        expectFailure(() -> ConservativeLeaseDeadline.fromGrant(0, 0, 100, 401, 2));
        expectFailure(() -> ConservativeLeaseDeadline.fromGrant(0, 0, 100, 120, -1));
        expectFailure(() -> ConservativeLeaseDeadline.fromGrant(Long.MAX_VALUE, 0, 100, 120, 2));
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
            throw new AssertionError("invalid deadline accepted");
        } catch (IllegalArgumentException | ArithmeticException expected) {
            // Expected failure.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
