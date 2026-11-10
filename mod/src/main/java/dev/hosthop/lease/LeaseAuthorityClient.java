package dev.hosthop.lease;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.JsonObject;
import dev.hosthop.storage.GoogleDriveSnapshotStore;
import dev.hosthop.relay.RelayAddress;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** Blocking HTTPS lease transport; invoke off the Minecraft thread. Not wired to hosting. */
public final class LeaseAuthorityClient {
    private static final int MAX_REPLY_BYTES = 16_384;
    private static final String ID = "[A-Za-z0-9_-]{1,80}";
    private final URI base;
    private final String token;
    private final Exchange exchange;
    private final LongSupplier nanos;
    private final LongSupplier millis;

    public record Reply(int status, String body) {}
    /** No matching publication was found; it may still be in flight. */
    public static final class PublicationNotFound extends IOException {
        private PublicationNotFound() { super("Publication was not found"); }
    }
    public static final class LeaseExpired extends IOException {
        private LeaseExpired() { super("The original lease is no longer active"); }
    }
    public record Grant(long epoch, long generation, ConservativeLeaseDeadline deadline) {}
    public record Endpoint(String world, long epoch, long generation, long revision, String address,
                           ConservativeLeaseDeadline deadline) {}
    public record Snapshot(long generation, GoogleDriveSnapshotStore.ObjectRef object) {}
    public record Checkpoint(String id, long generation, GoogleDriveSnapshotStore.ObjectRef object, String kind, long createdAt) {}
    public record EndpointLease(long epoch, long revision, String address, ConservativeLeaseDeadline deadline) {}
    public record HostStatus(boolean held, Endpoint endpoint) {}
    @FunctionalInterface public interface Exchange { Reply send(HttpRequest request) throws IOException, InterruptedException; }

    public LeaseAuthorityClient(URI base, String token) {
        this(base, token, httpExchange(), System::nanoTime, System::currentTimeMillis);
    }

    LeaseAuthorityClient(URI base, String token, Exchange exchange, LongSupplier nanos, LongSupplier millis) {
        if (base == null || !"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null
                || !(base.getPath().isEmpty() || base.getPath().equals("/"))) throw new IllegalArgumentException("Invalid authority URL");
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}") || exchange == null || nanos == null || millis == null)
            throw new IllegalArgumentException("Invalid authority configuration");
        this.base = base;
        this.token = token;
        this.exchange = exchange;
        this.nanos = nanos;
        this.millis = millis;
    }

    public Grant acquire(String world, String lease, int duration, long marginSeconds) throws IOException, InterruptedException {
        return request("acquire", world, lease, duration, 0, null, marginSeconds);
    }

    /** Recover a lost acquisition response; owner and duration must match the original request. */
    public Grant lookupAcquisition(String world, String lease, int duration, String expectedOwner, long marginSeconds)
            throws IOException, InterruptedException {
        if (expectedOwner == null || !expectedOwner.matches(ID)) throw new IllegalArgumentException("Invalid owner");
        return request("acquire-status", world, lease, duration, 0, expectedOwner, marginSeconds);
    }

    public Grant renew(String world, String lease, int duration, long expectedEpoch, long marginSeconds) throws IOException, InterruptedException {
        if (expectedEpoch < 1) throw new IllegalArgumentException("Invalid epoch");
        return request("renew", world, lease, duration, expectedEpoch, null, marginSeconds);
    }

    /** Endpoint authenticity depends on the configured HTTPS authority and its authorization. */
    public Endpoint endpoint(String world, long marginSeconds) throws IOException, InterruptedException {
        if (world == null || !world.matches(ID)) throw new IllegalArgumentException("Invalid world");
        var request = HttpRequest.newBuilder(base.resolve("/endpoint-status")).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"world\":\"" + world + "\"}", StandardCharsets.UTF_8)).build();
        long startNanos = nanos.getAsLong();
        long startMillis = millis.getAsLong();
        Reply reply = exchange.send(request);
        if (reply == null || reply.status() != 200 || reply.body() == null || reply.body().length() > MAX_REPLY_BYTES)
            throw new IOException("No usable endpoint");
        var fields = parseFields(reply.body());
        long epoch = required(fields.numbers(), "epoch");
        long generation = required(fields.numbers(), "generation");
        long revision = required(fields.numbers(), "endpoint_revision");
        long serverNow = required(fields.numbers(), "server_now");
        long expires = required(fields.numbers(), "expires");
        String address = fields.strings().get("endpoint");
        if (epoch < 1 || generation < 0 || revision < 0 || serverNow < 0 || !RelayAddress.valid(address))
            throw new IOException("Invalid endpoint response");
        try {
            var deadline = ConservativeLeaseDeadline.fromGrant(startNanos, startMillis, serverNow, expires, marginSeconds);
            if (!deadline.isUsable(nanos.getAsLong(), millis.getAsLong())) throw new IOException("Endpoint expired in transit");
            return new Endpoint(world, epoch, generation, revision, address, deadline);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new IOException("Invalid endpoint response", invalid);
        }
    }

    /** Distinguishes an unowned world from a held lease whose relay is not ready. */
    public HostStatus hostStatus(String world, long marginSeconds) throws IOException, InterruptedException {
        if (world == null || !world.matches(ID)) throw new IllegalArgumentException("Invalid world");
        var body = new JsonObject(); body.addProperty("world", world);
        long startNanos = nanos.getAsLong(), startMillis = millis.getAsLong();
        var fields = post("host-status", body);
        long held = required(fields.numbers(), "held"), epoch = required(fields.numbers(), "epoch");
        long generation = required(fields.numbers(), "generation"), revision = required(fields.numbers(), "endpoint_revision");
        long serverNow = required(fields.numbers(), "server_now"), expires = required(fields.numbers(), "expires");
        if ((held != 0 && held != 1) || epoch < 0 || generation < 0 || revision < 0 || serverNow < 0)
            throw new IOException("Invalid host status");
        String address = fields.strings().get("endpoint");
        if (address == null) return new HostStatus(held == 1, null);
        if (held != 1 || epoch < 1 || !RelayAddress.valid(address)) throw new IOException("Invalid host endpoint");
        try {
            var deadline = ConservativeLeaseDeadline.fromGrant(startNanos, startMillis, serverNow, expires, marginSeconds);
            if (!deadline.isUsable(nanos.getAsLong(), millis.getAsLong())) return new HostStatus(true, null);
            return new HostStatus(true, new Endpoint(world, epoch, generation, revision, address, deadline));
        } catch (IllegalArgumentException | ArithmeticException invalid) { throw new IOException("Invalid host status", invalid); }
    }

    public EndpointLease endpointRevision(String world, String lease, long epoch, long marginSeconds) throws IOException, InterruptedException {
        return endpointUpdate("endpoint-revision", world, lease, epoch, -1, null, marginSeconds);
    }

    /** A null address revokes publication; expectedRevision fences delayed callbacks. */
    public EndpointLease updateEndpoint(String world, String lease, long epoch, long expectedRevision, String address, long marginSeconds)
            throws IOException, InterruptedException {
        if (expectedRevision < 0 || (address != null && !RelayAddress.valid(address)))
            throw new IllegalArgumentException("Invalid endpoint update");
        return endpointUpdate("endpoint-update", world, lease, epoch, expectedRevision, address, marginSeconds);
    }

    private EndpointLease endpointUpdate(String route, String world, String lease, long epoch, long revision, String address, long margin)
            throws IOException, InterruptedException {
        if (world == null || !world.matches(ID) || lease == null || !lease.matches(ID) || epoch < 1)
            throw new IllegalArgumentException("Invalid lease");
        var body = new JsonObject();
        body.addProperty("world", world); body.addProperty("lease", lease); body.addProperty("epoch", epoch);
        if (revision >= 0) {
            body.addProperty("revision", revision);
            if (address == null) body.add("endpoint", com.google.gson.JsonNull.INSTANCE); else body.addProperty("endpoint", address);
        }
        long startNanos = nanos.getAsLong(), startMillis = millis.getAsLong();
        var fields = post(route, body);
        long returnedEpoch = required(fields.numbers(), "epoch"), returnedRevision = required(fields.numbers(), "endpoint_revision");
        long now = required(fields.numbers(), "server_now"), expiry = required(fields.numbers(), "expires");
        String returnedAddress = fields.strings().get("endpoint");
        if (returnedEpoch != epoch || returnedRevision < 0 || now < 0
                || (revision >= 0 && (returnedRevision != revision + 1 || !java.util.Objects.equals(returnedAddress, address)))
                || (returnedAddress != null && !RelayAddress.valid(returnedAddress)))
            throw new IOException("Invalid endpoint update receipt");
        try {
            var deadline = ConservativeLeaseDeadline.fromGrant(startNanos, startMillis, now, expiry, margin);
            if (!deadline.isUsable(nanos.getAsLong(), millis.getAsLong())) throw new IOException("Endpoint update expired");
            return new EndpointLease(epoch, returnedRevision, returnedAddress, deadline);
        } catch (IllegalArgumentException | ArithmeticException invalid) { throw new IOException("Invalid endpoint update receipt", invalid); }
    }

    public Snapshot snapshot(String world) throws IOException, InterruptedException {
        if (world == null || !world.matches(ID)) throw new IllegalArgumentException("Invalid world");
        var body = new JsonObject(); body.addProperty("world", world);
        var fields = post("snapshot-status", body);
        long generation = required(fields.numbers(), "generation");
        long size = required(fields.numbers(), "object_size");
        String digest = fields.strings().get("digest"), objectId = fields.strings().get("object_id");
        if (generation < 1 || size < 1 || size > 68_719_476_736L || digest == null || !digest.matches("[0-9a-f]{64}")
                || objectId == null || !objectId.matches("[A-Za-z0-9_-]{1,200}")) throw new IOException("Invalid snapshot reference");
        return new Snapshot(generation, new GoogleDriveSnapshotStore.ObjectRef(objectId, digest, size));
    }

    /** Returns up to four committed snapshot references, newest first. */
    public List<Snapshot> history(String world) throws IOException, InterruptedException {
        if (world == null || !world.matches(ID)) throw new IllegalArgumentException("Invalid world");
        var body = new JsonObject(); body.addProperty("world", world);
        var request = HttpRequest.newBuilder(base.resolve("/history")).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        var reply = exchange.send(request);
        if (reply == null || reply.status() != 200 || reply.body() == null || reply.body().length() > MAX_REPLY_BYTES)
            throw new IOException("History request failed");
        var snapshots = new java.util.ArrayList<Snapshot>();
        try (var reader = new JsonReader(new StringReader(reply.body()))) {
            reader.setLenient(false);
            reader.beginObject();
            if (!reader.hasNext() || !reader.nextName().equals("snapshots")) throw new IOException("Invalid history response");
            reader.beginArray();
            long previous = -1;
            while (reader.hasNext()) {
                if (snapshots.size() >= 4) throw new IOException("Too many history entries");
                var names = new HashSet<String>();
                long generation = -1, size = -1; String digest = null, object = null;
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (!names.add(key)) throw new IOException("Duplicate history field");
                    switch (key) {
                        case "generation", "object_size" -> {
                            if (reader.peek() != JsonToken.NUMBER) throw new IOException("Invalid history number");
                            String number = reader.nextString();
                            if (!number.matches("(0|[1-9][0-9]*)")) throw new IOException("Invalid history number");
                            long value = Long.parseLong(number);
                            if (key.equals("generation")) generation = value; else size = value;
                        }
                        case "digest", "object_id" -> {
                            if (reader.peek() != JsonToken.STRING) throw new IOException("Invalid history text");
                            if (key.equals("digest")) digest = reader.nextString(); else object = reader.nextString();
                        }
                        default -> throw new IOException("Unknown history field");
                    }
                }
                reader.endObject();
                if (names.size() != 4 || generation < 1 || (previous != -1 && generation >= previous) || size < 1 || size > 68_719_476_736L
                        || digest == null || !digest.matches("[0-9a-f]{64}") || object == null || !object.matches("[A-Za-z0-9_-]{1,200}"))
                    throw new IOException("Invalid history entry");
                snapshots.add(new Snapshot(generation, new GoogleDriveSnapshotStore.ObjectRef(object, digest, size)));
                previous = generation;
            }
            reader.endArray();
            if (reader.hasNext()) throw new IOException("Unexpected history field");
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing history data");
            return List.copyOf(snapshots);
        } catch (IllegalStateException | NumberFormatException invalid) { throw new IOException("Invalid history response", invalid); }
    }

    public void abort(String world, String owner, String lease, long epoch, long baseGeneration) throws IOException, InterruptedException {
        if (world == null || !world.matches(ID) || owner == null || !owner.matches(ID) || lease == null || !lease.matches(ID)
                || epoch < 1 || baseGeneration < 0) throw new IllegalArgumentException("Invalid lease abort");
        var body = new JsonObject();
        body.addProperty("world", world); body.addProperty("lease", lease); body.addProperty("epoch", epoch); body.addProperty("baseGeneration", baseGeneration);
        var fields = post("abort", body);
        if (!world.equals(fields.strings().get("world_id")) || !owner.equals(fields.strings().get("owner"))
                || !lease.equals(fields.strings().get("lease_id")) || epoch != required(fields.numbers(), "epoch")
                || baseGeneration != required(fields.numbers(), "base_generation")) throw new IOException("Abort receipt mismatch");
    }

    /** Fences a delayed acquisition or aborts its unstarted grant; safe to retry. */
    public void cancelAcquisition(String world, String owner, String lease) throws IOException, InterruptedException {
        if (world == null || !world.matches(ID) || owner == null || !owner.matches(ID) || lease == null || !lease.matches(ID))
            throw new IllegalArgumentException("Invalid acquisition cancellation");
        var body = new JsonObject();
        body.addProperty("world", world); body.addProperty("lease", lease);
        var fields = post("cancel-acquisition", body);
        if (!world.equals(fields.strings().get("world_id")) || !owner.equals(fields.strings().get("owner"))
                || !lease.equals(fields.strings().get("lease_id"))) throw new IOException("Cancellation receipt mismatch");
    }

    /** Publish only after the server is stopped and the uploaded object has been verified. */
    public PublicationRecord commit(PublicationRecord operation, String driveToken) throws IOException, InterruptedException {
        if (driveToken == null || driveToken.isBlank() || driveToken.length() > 2048 || driveToken.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid Drive token");
        var body = publicationBody(operation);
        body.addProperty("driveToken", driveToken);
        return matchPublication(operation, post("commit", body));
    }

    public PublicationRecord commitStatus(PublicationRecord operation) throws IOException, InterruptedException {
        return matchPublication(operation, post("commit-status", publicationBody(operation)));
    }

    /** Record a verified snapshot without advancing or releasing the active lease. */
    public CheckpointRecord checkpoint(CheckpointRecord operation, String driveToken) throws IOException, InterruptedException {
        return checkpoint(operation, driveToken, null);
    }

    /** An empty base identifies a managed full; null identifies an older client without dependency data. */
    public CheckpointRecord checkpoint(CheckpointRecord operation, String driveToken, String baseId) throws IOException, InterruptedException {
        if (operation == null || driveToken == null || driveToken.isBlank() || driveToken.length() > 2048
                || driveToken.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid checkpoint");
        if (baseId != null && !("full".equals(operation.kind()) ? baseId.isEmpty() : baseId.matches("[A-Za-z0-9_-]{1,200}")))
            throw new IllegalArgumentException("Invalid checkpoint base");
        var body = new JsonObject();
        body.addProperty("world", operation.world()); body.addProperty("checkpoint", operation.id());
        body.addProperty("lease", operation.lease()); body.addProperty("epoch", operation.epoch());
        body.addProperty("generation", operation.generation()); body.addProperty("digest", operation.object().sha256());
        body.addProperty("fileId", operation.object().fileId()); body.addProperty("size", operation.object().size());
        body.addProperty("kind", operation.kind()); body.addProperty("driveToken", driveToken);
        if (baseId != null) body.addProperty("baseFileId", baseId);
        var fields = post("checkpoint", body);
        var s = fields.strings(); var n = fields.numbers();
        if (!operation.world().equals(s.get("world_id")) || !operation.id().equals(s.get("checkpoint_id"))
                || !operation.owner().equals(s.get("owner")) || !operation.lease().equals(s.get("lease_id"))
                || !operation.object().fileId().equals(s.get("object_id")) || !operation.object().sha256().equals(s.get("digest"))
                || !operation.kind().equals(s.get("kind")) || operation.epoch() != required(n, "epoch")
                || operation.generation() != required(n, "generation") || operation.object().size() != required(n, "object_size")
                || n.containsKey("managed") != s.containsKey("base_object_id")
                || (n.containsKey("managed") && (n.get("managed") != (baseId == null ? 0 : 1)
                    || !java.util.Objects.equals(baseId == null ? "" : baseId, s.get("base_object_id")))))
            throw new IOException("Checkpoint receipt mismatch");
        return new CheckpointRecord(operation.world(), operation.id(), operation.owner(), operation.lease(), operation.epoch(),
                operation.generation(), operation.object(), operation.kind(), required(n, "created_at"));
    }

    public boolean fullCheckpointDue(String world) throws IOException, InterruptedException {
        if (world == null || !world.matches(ID)) throw new IllegalArgumentException("Invalid world");
        var body = new JsonObject(); body.addProperty("world", world);
        var fields = post("checkpoint-full", body).numbers();
        long value = required(fields, "created_at"), now = required(fields, "server_now");
        if (value < 0 || now < 0) throw new IOException("Invalid checkpoint time");
        return value == 0 || now < value || now - value >= 24 * 60 * 60;
    }

    public List<Checkpoint> checkpointHistory(String world) throws IOException, InterruptedException {
        return checkpoints(world, "checkpoint-history");
    }

    public List<Checkpoint> checkpointPruneCandidates(String world) throws IOException, InterruptedException {
        return checkpoints(world, "checkpoint-prune");
    }

    public List<Checkpoint> checkpointTrashPending(String world) throws IOException, InterruptedException {
        return checkpoints(world, "checkpoint-trash-pending");
    }

    public void checkpointTrashDone(String world, Checkpoint checkpoint) throws IOException, InterruptedException {
        if (world == null || !world.matches(ID) || checkpoint == null) throw new IllegalArgumentException("Invalid checkpoint");
        var body = new JsonObject(); body.addProperty("world", world); body.addProperty("checkpoint", checkpoint.id());
        body.addProperty("fileId", checkpoint.object().fileId()); body.addProperty("digest", checkpoint.object().sha256()); body.addProperty("size", checkpoint.object().size());
        var fields = post("checkpoint-trash-done", body);
        if (!world.equals(fields.strings().get("world_id")) || !checkpoint.id().equals(fields.strings().get("checkpoint_id"))
                || !checkpoint.object().fileId().equals(fields.strings().get("object_id")) || !checkpoint.object().sha256().equals(fields.strings().get("digest"))
                || checkpoint.object().size() != required(fields.numbers(), "object_size")) throw new IOException("Checkpoint cleanup receipt mismatch");
    }

    private List<Checkpoint> checkpoints(String world, String route) throws IOException, InterruptedException {
        if (world == null || !world.matches(ID)) throw new IllegalArgumentException("Invalid world");
        var body = new JsonObject();
        body.addProperty("world", world);
        if (!route.equals("checkpoint-history")) body.addProperty("includeFull", true);
        var request = HttpRequest.newBuilder(base.resolve("/" + route)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        var reply = exchange.send(request);
        if (reply == null || reply.status() != 200 || reply.body() == null || reply.body().length() > MAX_REPLY_BYTES) throw new IOException("Checkpoint history unavailable");
        try {
            var root = com.google.gson.JsonParser.parseString(reply.body()).getAsJsonObject();
            var array = root.getAsJsonArray("checkpoints");
            if (array == null || array.size() > 32) throw new IOException("Invalid checkpoint history");
            var result = new java.util.ArrayList<Checkpoint>();
            var ids = new HashSet<String>();
            long previous = Long.MAX_VALUE;
            for (var element : array) {
                var item = element.getAsJsonObject();
                String id = item.get("checkpoint_id").getAsString(), digest = item.get("digest").getAsString();
                String file = item.get("object_id").getAsString(), kind = item.get("kind").getAsString();
                long generation = item.get("generation").getAsLong(), size = item.get("object_size").getAsLong(), created = item.get("created_at").getAsLong();
                if (!id.matches(ID) || !ids.add(id) || !digest.matches("[0-9a-f]{64}") || !file.matches("[A-Za-z0-9_-]{1,200}")
                        || !(kind.equals("full") || kind.equals("delta")) || generation < 0 || size < 1 || size > 68_719_476_736L
                        || created < 1 || created > previous) throw new IOException("Invalid checkpoint history");
                result.add(new Checkpoint(id, generation, new GoogleDriveSnapshotStore.ObjectRef(file, digest, size), kind, created));
                previous = created;
            }
            return List.copyOf(result);
        } catch (RuntimeException invalid) { throw new IOException("Invalid checkpoint history", invalid); }
    }

    public int checkpointRetention(String world) throws IOException, InterruptedException {
        if (world == null || !world.matches(ID)) throw new IllegalArgumentException("Invalid world");
        var body = new JsonObject(); body.addProperty("world", world);
        long value = required(post("checkpoint-retention", body).numbers(), "keep_delta");
        if (value < 1 || value > 28) throw new IOException("Invalid retention setting");
        return (int) value;
    }

    public void setCheckpointRetention(String world, int keep) throws IOException, InterruptedException {
        if (world == null || !world.matches(ID) || keep < 1 || keep > 28) throw new IllegalArgumentException("Invalid retention setting");
        var body = new JsonObject(); body.addProperty("world", world); body.addProperty("keep", keep);
        if (required(post("checkpoint-retention-set", body).numbers(), "keep_delta") != keep) throw new IOException("Retention setting mismatch");
    }

    /** Forget only a delta checkpoint after the caller has confirmed deletion. */
    public void deleteCheckpoint(String world, Checkpoint checkpoint) throws IOException, InterruptedException {
        deleteCheckpoint(world, checkpoint, 0);
    }

    public void pruneCheckpoint(String world, Checkpoint checkpoint, int keep) throws IOException, InterruptedException {
        if (keep < 1 || keep > 28) throw new IllegalArgumentException("Invalid retention setting");
        deleteCheckpoint(world, checkpoint, keep);
    }

    private void deleteCheckpoint(String world, Checkpoint checkpoint, int keep) throws IOException, InterruptedException {
        if (world == null || !world.matches(ID) || checkpoint == null || (keep == 0 && !"delta".equals(checkpoint.kind())))
            throw new IllegalArgumentException("Protected checkpoint");
        var body = new JsonObject(); body.addProperty("world", world); body.addProperty("checkpoint", checkpoint.id());
        body.addProperty("fileId", checkpoint.object().fileId()); body.addProperty("digest", checkpoint.object().sha256()); body.addProperty("size", checkpoint.object().size());
        if (keep != 0) body.addProperty("keep", keep);
        var fields = post(keep == 0 ? "checkpoint-delete" : "checkpoint-prune-delete", body);
        if (!world.equals(fields.strings().get("world_id")) || !checkpoint.id().equals(fields.strings().get("checkpoint_id"))
                || !checkpoint.object().fileId().equals(fields.strings().get("object_id")) || !checkpoint.object().sha256().equals(fields.strings().get("digest"))
                || checkpoint.object().size() != required(fields.numbers(), "object_size")) throw new IOException("Checkpoint deletion receipt mismatch");
    }

    /** Only a verified stopped host may request atomic publication and release of its expired lease. */
    public PublicationRecord recoverStopped(PublicationRecord operation, String driveToken) throws IOException, InterruptedException {
        if (driveToken == null || driveToken.isBlank() || driveToken.length() > 2048 || driveToken.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid Drive token");
        var body = publicationBody(operation);
        body.addProperty("driveToken", driveToken);
        return matchPublication(operation, post("recover-stopped", body));
    }

    public ReleaseRecord release(PublicationRecord operation) throws IOException, InterruptedException {
        return releaseRequest("release", operation);
    }

    public ReleaseRecord releaseStatus(PublicationRecord operation) throws IOException, InterruptedException {
        return releaseRequest("release-status", operation);
    }

    private ReleaseRecord releaseRequest(String route, PublicationRecord operation) throws IOException, InterruptedException {
        var body = publicationBody(operation);
        body.addProperty("resultGeneration", operation.resultGeneration());
        var fields = post(route, body);
        var strings = fields.strings();
        var receipt = new ReleaseRecord(strings.get("world_id"), strings.get("owner"), strings.get("lease_id"),
                required(fields.numbers(), "epoch"), strings.get("operation_id"), required(fields.numbers(), "result_generation"));
        if (!receipt.matches(operation)) throw new IOException("Release receipt mismatch");
        return receipt;
    }

    private static JsonObject publicationBody(PublicationRecord operation) {
        if (operation == null || !operation.worldId().matches(ID) || !operation.leaseId().matches(ID)
                || !operation.owner().matches(ID) || !operation.operationId().matches(ID) || !operation.digest().matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid publication");
        var body = new JsonObject();
        body.addProperty("world", operation.worldId()); body.addProperty("lease", operation.leaseId());
        body.addProperty("epoch", operation.epoch()); body.addProperty("operation", operation.operationId());
        body.addProperty("previousGeneration", operation.previousGeneration()); body.addProperty("digest", operation.digest());
        body.addProperty("fileId", operation.objectId()); body.addProperty("size", operation.objectSize());
        return body;
    }

    private static PublicationRecord matchPublication(PublicationRecord operation, Fields fields) throws IOException {
        var strings = fields.strings(); var numbers = fields.numbers();
        if (!operation.worldId().equals(strings.get("world_id")) || !operation.owner().equals(strings.get("owner"))
                || !operation.leaseId().equals(strings.get("lease_id")) || !operation.operationId().equals(strings.get("operation_id"))
                || !operation.digest().equals(strings.get("digest")) || !operation.objectId().equals(strings.get("object_id"))
                || operation.epoch() != required(numbers, "epoch") || operation.previousGeneration() != required(numbers, "previous_generation")
                || operation.objectSize() != required(numbers, "object_size") || operation.resultGeneration() != required(numbers, "result_generation"))
            throw new IOException("Publication receipt mismatch");
        return operation;
    }

    private Fields post(String route, JsonObject body) throws IOException, InterruptedException {
        String text = body.toString();
        if (text.getBytes(StandardCharsets.UTF_8).length > 4096) throw new IOException("Authority request too large");
        var request = HttpRequest.newBuilder(base.resolve("/" + route)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8)).build();
        Reply reply = exchange.send(request);
        if (reply != null && reply.status() == 404 && route.equals("commit-status")) throw new PublicationNotFound();
        if (reply == null || reply.status() != 200 || reply.body() == null || reply.body().length() > MAX_REPLY_BYTES)
            throw new IOException("Authority request failed");
        return parseFields(reply.body());
    }

    private Grant request(String route, String world, String lease, int duration, long expectedEpoch, String expectedOwner, long margin) throws IOException, InterruptedException {
        if (world == null || !world.matches(ID) || lease == null || !lease.matches(ID) || duration < 1 || duration > 300)
            throw new IllegalArgumentException("Invalid lease request");
        String body = "{\"world\":\"" + world + "\",\"lease\":\"" + lease + "\",\"duration\":" + duration
                + (expectedEpoch == 0 ? "" : ",\"epoch\":" + expectedEpoch) + "}";
        var request = HttpRequest.newBuilder(base.resolve("/" + route)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        long startNanos = nanos.getAsLong();
        long startMillis = millis.getAsLong();
        Reply reply = exchange.send(request);
        if (reply == null || reply.status() != 200 || reply.body() == null || reply.body().length() > MAX_REPLY_BYTES)
            throw new IOException("Lease authority did not grant the request");
        var fields = parseFields(reply.body());
        long epoch = required(fields.numbers(), "epoch");
        long generation = required(fields.numbers(), expectedOwner == null ? "generation" : "base_generation");
        long serverNow = required(fields.numbers(), "server_now");
        long expires = required(fields.numbers(), "expires");
        if (expectedOwner != null) {
            if (!expectedOwner.equals(fields.strings().get("owner")) || required(fields.numbers(), "duration_seconds") != duration)
                throw new IOException("Acquisition does not match original request");
            long active = required(fields.numbers(), "active");
            if (active == 0) throw new LeaseExpired();
            if (active != 1) throw new IOException("Invalid acquisition status");
        }
        if (epoch < 1 || generation < 0 || serverNow < 0 || (expectedEpoch != 0 && epoch != expectedEpoch)) throw new IOException("Invalid lease grant");
        try {
            var deadline = ConservativeLeaseDeadline.fromGrant(startNanos, startMillis, serverNow, expires, margin);
            if (!deadline.isUsable(nanos.getAsLong(), millis.getAsLong())) throw new IOException("Lease grant expired in transit");
            return new Grant(epoch, generation, deadline);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new IOException("Invalid lease grant", invalid);
        }
    }

    private static long required(Map<String, Long> fields, String key) throws IOException {
        Long value = fields.get(key);
        if (value == null) throw new IOException("Incomplete lease grant");
        return value;
    }

    private record Fields(Map<String, Long> numbers, Map<String, String> strings) {}

    private static Fields parseFields(String text) throws IOException {
        var values = new HashMap<String, Long>();
        var names = new HashSet<String>();
        var strings = new HashMap<String, String>();
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!names.add(name)) throw new IOException("Duplicate response field");
                if (name.equals("epoch") || name.equals("generation") || name.equals("base_generation")
                        || name.equals("duration_seconds") || name.equals("active") || name.equals("held") || name.equals("endpoint_revision")
                        || name.equals("previous_generation") || name.equals("result_generation") || name.equals("object_size")
                        || name.equals("server_now") || name.equals("expires") || name.equals("created_at") || name.equals("keep_delta")
                        || name.equals("managed")) {
                    if (reader.peek() != JsonToken.NUMBER) throw new IOException("Invalid response field");
                    String value = reader.nextString();
                    if (!value.matches("-?(0|[1-9][0-9]*)")) throw new IOException("Invalid response number");
                    values.put(name, Long.parseLong(value));
                } else if (java.util.Set.of("owner", "endpoint", "world_id", "operation_id", "lease_id", "digest",
                        "object_id", "checkpoint_id", "kind", "base_object_id").contains(name)) {
                    if (name.equals("endpoint") && reader.peek() == JsonToken.NULL) { reader.nextNull(); continue; }
                    if (reader.peek() != JsonToken.STRING) throw new IOException("Invalid response text");
                    strings.put(name, reader.nextString());
                } else reader.skipValue();
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing response data");
            return new Fields(values, strings);
        } catch (IllegalStateException | NumberFormatException invalid) {
            throw new IOException("Invalid lease response", invalid);
        }
    }

    private static Exchange httpExchange() {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        return httpExchange(client, Duration.ofSeconds(10));
    }

    static Exchange httpExchange(HttpClient client, Duration timeout) {
        return request -> {
            var pending = client.sendAsync(request, info -> new LimitedBody());
            try {
                var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return new Reply(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
            } catch (TimeoutException | ExecutionException failure) {
                pending.cancel(true);
                throw new IOException("Lease request failed", failure);
            } catch (InterruptedException interrupted) {
                pending.cancel(true);
                throw interrupted;
            }
        };
    }

    /** Bounded streaming subscriber; the outer timeout covers headers and body. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > MAX_REPLY_BYTES - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Lease response too large"));
                    return;
                }
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
