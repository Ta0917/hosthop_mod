package dev.hosthop.lease;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class LeaseAuthorityClientTest {
    public static void main(String[] args) throws Exception {
        var nanos = new AtomicLong();
        var millis = new AtomicLong();
        var reply = new AtomicReference<>(new LeaseAuthorityClient.Reply(200,
                "{\"epoch\":4,\"generation\":3,\"server_now\":100,\"expires\":130}"));
        var client = new LeaseAuthorityClient(URI.create("https://authority.example/"), "a".repeat(43),
                request -> {
                    check(java.util.Set.of("/acquire", "/renew", "/acquire-status", "/endpoint-status").contains(request.uri().getPath()));
                    check(request.headers().firstValue("Authorization").orElseThrow().equals("Bearer " + "a".repeat(43)));
                    return reply.get();
                }, nanos::get, millis::get);
        var grant = client.acquire("world", "lease", 30, 2);
        check(grant.epoch() == 4 && grant.generation() == 3 && grant.deadline().isUsable(1_000_000_000L, 1000));
        check(client.renew("world", "lease", 30, 4, 2).epoch() == 4);
        expectFailure(() -> client.renew("world", "lease", 30, 5, 2));
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"owner\":\"owner\",\"epoch\":4,\"base_generation\":3,\"duration_seconds\":30,\"active\":1,\"server_now\":100,\"expires\":130}"));
        check(client.lookupAcquisition("world", "lease", 30, "owner", 2).generation() == 3);
        expectFailure(() -> client.lookupAcquisition("world", "lease", 30, "other", 2));
        expectFailure(() -> client.lookupAcquisition("world", "lease", 29, "owner", 2));
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"owner\":\"owner\",\"epoch\":4,\"base_generation\":3,\"duration_seconds\":30,\"active\":0,\"server_now\":100,\"expires\":130}"));
        expectFailure(() -> client.lookupAcquisition("world", "lease", 30, "owner", 2));
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"endpoint\":\"example.e4mc.link\",\"endpoint_revision\":7,\"epoch\":4,\"generation\":3,\"server_now\":100,\"expires\":130}"));
        var endpoint = client.endpoint("world", 2);
        check(endpoint.epoch() == 4 && endpoint.revision() == 7 && endpoint.address().equals("example.e4mc.link"));
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"endpoint\":\"example.evil.invalid\",\"endpoint_revision\":7,\"epoch\":4,\"generation\":3,\"server_now\":100,\"expires\":130}"));
        expectFailure(() -> client.endpoint("world", 2));
        reply.set(new LeaseAuthorityClient.Reply(409, "{}"));
        expectFailure(() -> client.acquire("world", "lease", 30, 2));
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"epoch\":4,\"epoch\":5,\"generation\":3,\"server_now\":100,\"expires\":130}"));
        expectFailure(() -> client.acquire("world", "lease", 30, 2));
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"epoch\":4,\"generation\":3,\"server_now\":100,\"expires\":1e2}"));
        expectFailure(() -> client.acquire("world", "lease", 30, 2));
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"epoch\":4,\"generation\":3,\"server_now\":100,\"expires\":130} trailing"));
        expectFailure(() -> client.acquire("world", "lease", 30, 2));
        reply.set(new LeaseAuthorityClient.Reply(200, "x".repeat(20_000)));
        expectFailure(() -> client.acquire("world", "lease", 30, 2));
        var delayed = new LeaseAuthorityClient(URI.create("https://authority.example"), "a".repeat(43), request -> {
            nanos.set(30_000_000_000L); millis.set(30_000);
            return new LeaseAuthorityClient.Reply(200, "{\"epoch\":4,\"generation\":3,\"server_now\":100,\"expires\":130}");
        }, nanos::get, millis::get);
        expectFailure(() -> delayed.acquire("world", "lease", 30, 2));
        try {
            new LeaseAuthorityClient(URI.create("http://authority.example"), "a".repeat(43), request -> null, () -> 0, () -> 0);
            throw new AssertionError("insecure URL accepted");
        } catch (IllegalArgumentException expected) { }
        var subscriber = new LeaseAuthorityClient.LimitedBody();
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            @Override public void request(long count) { }
            @Override public void cancel() { cancelled.set(true); }
        });
        subscriber.onNext(java.util.List.of(java.nio.ByteBuffer.wrap(new byte[16_385])));
        check(cancelled.get());
        try {
            subscriber.getBody().toCompletableFuture().join();
            throw new AssertionError("oversized response accepted");
        } catch (java.util.concurrent.CompletionException expected) { }
        System.out.println("LeaseAuthorityClient checks passed");
    }

    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void expectFailure(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("invalid grant accepted"); }
        catch (IOException expected) { }
    }
}
