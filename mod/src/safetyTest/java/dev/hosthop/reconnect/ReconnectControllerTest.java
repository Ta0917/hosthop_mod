package dev.hosthop.reconnect;

public final class ReconnectControllerTest {
    public static void main(String[] args) {
        var controller = new ReconnectController();
        var session = controller.start("world", 4, 0);
        check(controller.connect(session, new ReconnectController.Destination("other", 5, "host"), 0) == null);
        check(controller.connect(session, new ReconnectController.Destination("world", 3, "host"), 0) == null);
        var destination = new ReconnectController.Destination("world", 5, "host");
        var first = controller.connect(session, destination, 0);
        check(first != null);
        check(controller.failed(session, first, ReconnectController.Failure.TRANSIENT, 0));
        check(controller.connect(session, destination, 999_999_999L) == null);
        var second = controller.connect(session, destination, 1_000_000_000L);
        check(second != null && !controller.connected(session, first));
        check(controller.failed(session, second, ReconnectController.Failure.AUTHENTICATION, 1_000_000_000L));
        check(controller.state() == ReconnectController.State.STOPPED);
        check(controller.connect(session, destination, 10_000_000_000L) == null);

        var next = controller.start("world", 5, 0);
        check(!controller.cancel(session));
        var active = controller.connect(next, destination, 0);
        check(controller.cancel(next));
        check(!controller.connected(next, active));

        var limited = controller.start("world", 5, 0);
        long now = 0;
        for (int i = 0; i < 5; i++) {
            var attempt = controller.connect(limited, destination, now);
            check(attempt != null);
            check(controller.failed(limited, attempt, ReconnectController.Failure.TRANSIENT, now));
            now += 1_000_000_000L << i;
        }
        check(controller.state() == ReconnectController.State.STOPPED);
        var reverse = controller.start("world", 5, 100);
        check(controller.connect(reverse, destination, 99) == null);
        check(controller.state() == ReconnectController.State.STOPPED);
        System.out.println("ReconnectController safety checks passed");
    }

    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
}
