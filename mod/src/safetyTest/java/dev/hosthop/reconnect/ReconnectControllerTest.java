package dev.hosthop.reconnect;

import dev.hosthop.lease.ConservativeLeaseDeadline;
import dev.hosthop.lease.LeaseAuthorityClient;

public final class ReconnectControllerTest {
    public static void main(String[] args) {
        long minute = java.util.concurrent.TimeUnit.MINUTES.toNanos(1);
        check(!ReconnectWaitPolicy.expired(0, 8 * minute) && ReconnectWaitPolicy.expired(0, 30 * minute));
        check(ReconnectWaitPolicy.pollTicks(0, minute) == 40 && ReconnectWaitPolicy.pollTicks(0, 8 * minute) == 200);
        var controller = new ReconnectController();
        var session = controller.start("world", 4, 0);
        check(controller.connect(session, destination("other", 5), 0, 0) == null);
        check(controller.connect(session, destination("world", 3), 0, 0) == null);
        var destination = destination("world", 5);
        var first = controller.connect(session, destination, 0, 0);
        check(first != null);
        check(controller.failed(session, first, ReconnectController.Failure.TRANSIENT, 0));
        check(controller.connect(session, destination, 999_999_999L, 999) == null);
        var second = controller.connect(session, destination, 1_000_000_000L, 1000);
        check(second != null && !controller.connected(session, first));
        check(controller.failed(session, second, ReconnectController.Failure.AUTHENTICATION, 1_000_000_000L));
        check(controller.state() == ReconnectController.State.STOPPED);
        check(controller.connect(session, destination, 10_000_000_000L, 10_000) == null);

        var next = controller.start("world", 5, 0);
        check(!controller.cancel(session));
        destination = destination("world", 5);
        var active = controller.connect(next, destination, 0, 0);
        check(controller.cancel(next));
        check(!controller.connected(next, active));

        var limited = controller.start("world", 5, 0);
        long now = 0;
        for (int i = 0; i < 5; i++) {
            var attempt = controller.connect(limited, destination, now, now / 1_000_000);
            check(attempt != null);
            check(controller.failed(limited, attempt, ReconnectController.Failure.TRANSIENT, now));
            now += 1_000_000_000L << i;
        }
        check(controller.state() == ReconnectController.State.STOPPED);
        var reverse = controller.start("world", 5, 100);
        check(controller.connect(reverse, destination, 99, 0) == null);
        check(controller.state() == ReconnectController.State.STOPPED);
        var expired = controller.start("world", 5, 0);
        check(controller.connect(expired, destination("world", 5), 27_000_000_000L, 27_000) == null);
        System.out.println("ReconnectController safety checks passed");
    }

    private static LeaseAuthorityClient.Endpoint destination(String world, long epoch) {
        return new LeaseAuthorityClient.Endpoint(world, epoch, 1, 1, "host.e4mc.link",
                ConservativeLeaseDeadline.fromGrant(0, 0, 100, 130, 2));
    }

    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
}
