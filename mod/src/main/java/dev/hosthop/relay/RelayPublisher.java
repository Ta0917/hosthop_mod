package dev.hosthop.relay;

import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.lease.LeaseCoordinator;
import dev.hosthop.lease.LocalHostGate;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Serializes e4mc session events and revision-fenced authority updates. */
public final class RelayPublisher implements E4mcRelayBridge.Listener {
    private final LeaseAuthorityClient authority;
    private final LeaseCoordinator.Acquisition acquisition;
    private final LocalHostGate gate;
    private final Executor serialExecutor;
    private final Runnable ready;
    private final Consumer<Throwable> failure;
    private final java.util.concurrent.ScheduledExecutorService timer;
    private final long reconnectSeconds;
    private volatile Object current;
    private long revision = -1;
    private Object published;
    /** Counts addresses published; a reconnect wait succeeds only if it advances. Confined to the serial executor. */
    private long publications;
    public boolean sessionOpen() { return current != null; }

    /** A relay drop while hosting stops the host immediately. */
    public RelayPublisher(LeaseAuthorityClient authority, LeaseCoordinator.Acquisition acquisition, LocalHostGate gate,
                          Executor serialExecutor, Runnable ready, Consumer<Throwable> failure) {
        this(authority, acquisition, gate, serialExecutor, ready, failure, null, 0);
    }

    /**
     * A relay drop while hosting withdraws the address and waits up to {@code reconnectSeconds} for e4mc to
     * open and publish a new session before stopping the host.
     */
    public RelayPublisher(LeaseAuthorityClient authority, LeaseCoordinator.Acquisition acquisition, LocalHostGate gate,
                          Executor serialExecutor, Runnable ready, Consumer<Throwable> failure,
                          java.util.concurrent.ScheduledExecutorService timer, long reconnectSeconds) {
        if (authority == null || acquisition == null || gate == null || serialExecutor == null || ready == null || failure == null
                || reconnectSeconds < 0 || (timer == null) != (reconnectSeconds == 0))
            throw new IllegalArgumentException("Missing relay publisher input");
        this.authority = authority; this.acquisition = acquisition; this.gate = gate;
        this.serialExecutor = serialExecutor; this.ready = ready; this.failure = failure;
        this.timer = timer; this.reconnectSeconds = reconnectSeconds;
    }

    @Override public void opened(Object token) {
        current = token;
        serialExecutor.execute(() -> {
            if (current != token) return;
            try {
                var state = authority.endpointRevision(acquisition.world(), acquisition.lease(), acquisition.epoch(), 2);
                revision = state.revision();
                if (state.address() != null) {
                    revision = authority.updateEndpoint(acquisition.world(), acquisition.lease(), acquisition.epoch(), revision, null, 2).revision();
                    published = null;
                }
            } catch (Exception error) { failed(error); }
        });
    }

    @Override public void assigned(Object token, String address) {
        serialExecutor.execute(() -> {
            if (current != token || gate.phase() != LocalHostGate.Phase.HOSTING) return;
            try {
                if (revision < 0) throw new IOException("Relay revision unavailable");
                var state = authority.updateEndpoint(acquisition.world(), acquisition.lease(), acquisition.epoch(), revision, address, 2);
                revision = state.revision();
                published = token;
                publications++;
                if (current != token || gate.phase() != LocalHostGate.Phase.HOSTING) revoke(token);
                else ready.run();
            } catch (Exception error) { failed(error); }
        });
    }

    @Override public void closed(Object token) {
        boolean wasCurrent = current == token;
        if (wasCurrent) current = null;
        serialExecutor.execute(() -> {
            try { revoke(token); }
            catch (Exception error) { failed(error); return; }
            if (!wasCurrent || gate.phase() != LocalHostGate.Phase.HOSTING) return;
            if (timer == null) { failed(new IOException("Relay disconnected")); return; }
            long before = publications;
            com.mojang.logging.LogUtils.getLogger().warn("HostHop relay disconnected; waiting up to {} s for a new relay session", reconnectSeconds);
            try {
                timer.schedule(() -> serialExecutor.execute(() -> {
                    if (publications == before && gate.phase() == LocalHostGate.Phase.HOSTING)
                        failed(new IOException("Relay did not reconnect"));
                }), reconnectSeconds, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.RejectedExecutionException shuttingDown) { failed(new IOException("Relay disconnected")); }
        });
    }

    private void revoke(Object token) throws IOException, InterruptedException {
        if (published != token) return;
        revision = authority.updateEndpoint(acquisition.world(), acquisition.lease(), acquisition.epoch(), revision, null, 2).revision();
        published = null;
    }

    private void failed(Throwable error) {
        gate.requireStop(acquisition.attempt());
        failure.accept(error);
    }
}
