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
                    check(java.util.Set.of("/acquire", "/renew", "/acquire-status", "/endpoint-status", "/endpoint-revision", "/endpoint-update", "/commit", "/commit-status", "/recover-stopped", "/snapshot-status", "/release", "/release-status", "/abort", "/host-status", "/history", "/checkpoint", "/checkpoint-history", "/checkpoint-full", "/checkpoint-delete", "/checkpoint-retention", "/checkpoint-retention-set", "/checkpoint-prune", "/checkpoint-prune-delete").contains(request.uri().getPath()));
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
        try { client.lookupAcquisition("world", "lease", 30, "owner", 2); throw new AssertionError("expired grant accepted"); }
        catch (LeaseAuthorityClient.LeaseExpired expected) { }
        expectFailure(() -> client.lookupAcquisition("world", "lease", 30, "other", 2));
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"endpoint\":\"example.us.e4mc.link\",\"endpoint_revision\":7,\"epoch\":4,\"generation\":3,\"server_now\":100,\"expires\":130}"));
        var endpoint = client.endpoint("world", 2);
        check(endpoint.epoch() == 4 && endpoint.revision() == 7 && endpoint.address().equals("example.us.e4mc.link"));
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"endpoint\":\"example.evil.invalid\",\"endpoint_revision\":7,\"epoch\":4,\"generation\":3,\"server_now\":100,\"expires\":130}"));
        expectFailure(() -> client.endpoint("world", 2));
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"held\":0,\"endpoint\":null,\"endpoint_revision\":0,\"epoch\":0,\"generation\":0,\"server_now\":100,\"expires\":0}"));
        check(!client.hostStatus("world", 2).held());
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"held\":1,\"endpoint\":null,\"endpoint_revision\":1,\"epoch\":1,\"generation\":0,\"server_now\":100,\"expires\":99}"));
        check(client.hostStatus("world", 2).held() && client.hostStatus("world", 2).endpoint() == null);
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"epoch\":4,\"endpoint_revision\":7,\"endpoint\":null,\"server_now\":100,\"expires\":130}"));
        check(client.endpointRevision("world", "lease", 4, 2).revision() == 7);
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"epoch\":4,\"endpoint_revision\":8,\"endpoint\":\"example.e4mc.link\",\"server_now\":100,\"expires\":130}"));
        check(client.updateEndpoint("world", "lease", 4, 7, "example.e4mc.link", 2).revision() == 8);
        expectFailure(() -> client.updateEndpoint("world", "lease", 4, 6, "example.e4mc.link", 2));
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"epoch\":4,\"endpoint_revision\":9,\"endpoint\":null,\"server_now\":100,\"expires\":130}"));
        check(client.updateEndpoint("world", "lease", 4, 8, null, 2).address() == null);
        String digest = "a".repeat(64);
        var publication = new PublicationRecord("world", "op", "owner", "lease", 4, 3, digest, "file-id", 42, 4);
        String receipt = "{\"world_id\":\"world\",\"operation_id\":\"op\",\"owner\":\"owner\",\"lease_id\":\"lease\",\"epoch\":4,\"previous_generation\":3,\"digest\":\"" + digest + "\",\"object_id\":\"file-id\",\"object_size\":42,\"result_generation\":4}";
        reply.set(new LeaseAuthorityClient.Reply(200, receipt));
        check(client.commit(publication, "drive-token").equals(publication));
        check(client.recoverStopped(publication, "drive-token").equals(publication));
        check(client.commitStatus(publication).equals(publication));
        var checkpoint = new CheckpointRecord("world", "checkpoint", "owner", "lease", 4, 3,
                new dev.hosthop.storage.GoogleDriveSnapshotStore.ObjectRef("file-id", digest, 42), "delta", 0);
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"world_id\":\"world\",\"checkpoint_id\":\"checkpoint\",\"owner\":\"owner\",\"lease_id\":\"lease\",\"epoch\":4,\"generation\":3,\"digest\":\"" + digest + "\",\"object_id\":\"file-id\",\"object_size\":42,\"kind\":\"delta\",\"created_at\":100}"));
        check(client.checkpoint(checkpoint, "drive-token").createdAt() == 100);
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"created_at\":100,\"server_now\":86500}"));
        check(client.fullCheckpointDue("world"));
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"created_at\":100,\"server_now\":86499}"));
        check(!client.fullCheckpointDue("world"));
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"checkpoints\":[{\"checkpoint_id\":\"checkpoint\",\"generation\":3,\"digest\":\"" + digest + "\",\"object_id\":\"file-id\",\"object_size\":42,\"kind\":\"delta\",\"created_at\":100}]}"));
        check(client.checkpointHistory("world").size() == 1 && client.checkpointHistory("world").get(0).id().equals("checkpoint"));
        var listed = client.checkpointHistory("world").get(0);
        check(client.checkpointPruneCandidates("world").size() == 1);
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"keep_delta\":4}"));
        check(client.checkpointRetention("world") == 4);
        client.setCheckpointRetention("world", 4);
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"world_id\":\"world\",\"checkpoint_id\":\"checkpoint\",\"object_id\":\"file-id\",\"object_size\":42,\"digest\":\"" + digest + "\"}"));
        client.deleteCheckpoint("world", listed);
        client.pruneCheckpoint("world", listed, 4);
        reply.set(new LeaseAuthorityClient.Reply(200, receipt.replace("file-id", "wrong-file")));
        expectFailure(() -> client.commitStatus(publication));
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"generation\":4,\"digest\":\"" + digest + "\",\"object_id\":\"file-id\",\"object_size\":42}"));
        check(client.snapshot("world").object().fileId().equals("file-id"));
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"world_id\":\"world\",\"operation_id\":\"op\",\"owner\":\"owner\",\"lease_id\":\"lease\",\"epoch\":4,\"result_generation\":4}"));
        check(client.release(publication).matches(publication) && client.releaseStatus(publication).matches(publication));
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"world_id\":\"world\",\"owner\":\"owner\",\"lease_id\":\"lease\",\"epoch\":4,\"base_generation\":3}"));
        client.abort("world", "owner", "lease", 4, 3);
        expectFailure(() -> client.abort("world", "other", "lease", 4, 3));
        String item = "{\"generation\":4,\"digest\":\"" + "a".repeat(64) + "\",\"object_id\":\"file\",\"object_size\":42}";
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"snapshots\":[" + item + "]}"));
        check(client.history("world").size() == 1 && client.history("world").get(0).generation() == 4);
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"snapshots\":[" + item + "," + item + "]}"));
        expectFailure(() -> client.history("world"));
        reply.set(new LeaseAuthorityClient.Reply(200, "{\"snapshots\":[]}"));
        check(client.history("world").isEmpty());
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
