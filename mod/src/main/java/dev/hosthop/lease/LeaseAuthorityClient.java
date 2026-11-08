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
    public record Grant(long epoch, long generation, ConservativeLeaseDeadline deadline) {}
    public record Endpoint(String world, long epoch, long generation, long revision, String address,
                           ConservativeLeaseDeadline deadline) {}
    public record Snapshot(long generation, GoogleDriveSnapshotStore.ObjectRef object) {}
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
        if (expectedOwner != null && (!expectedOwner.equals(fields.strings().get("owner"))
                || required(fields.numbers(), "duration_seconds") != duration || required(fields.numbers(), "active") != 1))
            throw new IOException("Acquisition does not match active request");
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
                        || name.equals("server_now") || name.equals("expires")) {
                    if (reader.peek() != JsonToken.NUMBER) throw new IOException("Invalid response field");
                    String value = reader.nextString();
                    if (!value.matches("-?(0|[1-9][0-9]*)")) throw new IOException("Invalid response number");
                    values.put(name, Long.parseLong(value));
                } else if (java.util.Set.of("owner", "endpoint", "world_id", "operation_id", "lease_id", "digest", "object_id").contains(name)) {
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
