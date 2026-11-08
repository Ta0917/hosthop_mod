package dev.hosthop.lease;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
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
        return request("acquire", world, lease, duration, 0, marginSeconds);
    }

    public Grant renew(String world, String lease, int duration, long expectedEpoch, long marginSeconds) throws IOException, InterruptedException {
        if (expectedEpoch < 1) throw new IllegalArgumentException("Invalid epoch");
        return request("renew", world, lease, duration, expectedEpoch, marginSeconds);
    }

    private Grant request(String route, String world, String lease, int duration, long expectedEpoch, long margin) throws IOException, InterruptedException {
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
        var fields = parseNumbers(reply.body());
        long epoch = required(fields, "epoch");
        long generation = required(fields, "generation");
        long serverNow = required(fields, "server_now");
        long expires = required(fields, "expires");
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

    private static Map<String, Long> parseNumbers(String text) throws IOException {
        var values = new HashMap<String, Long>();
        var names = new HashSet<String>();
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!names.add(name)) throw new IOException("Duplicate response field");
                if (name.equals("epoch") || name.equals("generation") || name.equals("server_now") || name.equals("expires")) {
                    if (reader.peek() != JsonToken.NUMBER) throw new IOException("Invalid response field");
                    String value = reader.nextString();
                    if (!value.matches("-?(0|[1-9][0-9]*)")) throw new IOException("Invalid response number");
                    values.put(name, Long.parseLong(value));
                } else reader.skipValue();
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing response data");
            return values;
        } catch (IllegalStateException | NumberFormatException invalid) {
            throw new IOException("Invalid lease response", invalid);
        }
    }

    private static Exchange httpExchange() {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        return request -> {
            var pending = client.sendAsync(request, info -> new LimitedBody());
            try {
                var response = pending.get(10, TimeUnit.SECONDS);
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
