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
    private volatile Object current;
    private long revision = -1;
    private Object published;
    public boolean sessionOpen() { return current != null; }

    public RelayPublisher(LeaseAuthorityClient authority, LeaseCoordinator.Acquisition acquisition, LocalHostGate gate,
                          Executor serialExecutor, Runnable ready, Consumer<Throwable> failure) {
        if (authority == null || acquisition == null || gate == null || serialExecutor == null || ready == null || failure == null)
            throw new IllegalArgumentException("Missing relay publisher input");
        this.authority = authority; this.acquisition = acquisition; this.gate = gate;
        this.serialExecutor = serialExecutor; this.ready = ready; this.failure = failure;
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
            if (wasCurrent && gate.phase() == LocalHostGate.Phase.HOSTING) failed(new IOException("Relay disconnected"));
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
