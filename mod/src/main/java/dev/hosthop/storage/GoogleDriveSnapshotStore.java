package dev.hosthop.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hosthop.snapshot.SnapshotVerifier;
import dev.hosthop.snapshot.DiskSpace;
import dev.hosthop.snapshot.BinaryDelta;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Google Drive binary snapshots; caller supplies a short-lived OAuth access token. */
public final class GoogleDriveSnapshotStore implements SnapshotStore {
    private static final int CHUNK = 8 * 1024 * 1024;
    private static final int MAX_REPLY = 64 * 1024;
    private static final long MAX_SIZE = 64L * 1024 * 1024 * 1024;
    private static final URI INITIATE = URI.create("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name,size,sha256Checksum,parents,trashed");
    private static final URI QUOTA = URI.create("https://www.googleapis.com/drive/v3/about?fields=storageQuota%28limit%2Cusage%29%2CmaxUploadSize");
    public record ObjectRef(String fileId, String sha256, long size) {}
    record Reply(int status, HttpHeaders headers, byte[] body) {}
    @FunctionalInterface interface Transport { Reply send(HttpRequest request) throws IOException, InterruptedException; }
    @FunctionalInterface interface Downloader { int receive(HttpRequest request, FileChannel output, long size, String digest, java.util.function.LongConsumer progress) throws IOException, InterruptedException; }
    private final Transport transport;
    private final Downloader downloader;

    public GoogleDriveSnapshotStore() {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        this.transport = httpTransport(client);
        this.downloader = httpDownloader(client);
    }
    GoogleDriveSnapshotStore(Transport transport) { this(transport, null); }
    GoogleDriveSnapshotStore(Transport transport, Downloader downloader) { this.transport = transport; this.downloader = downloader; }

    public static final class QuotaExceeded extends IOException {
        private QuotaExceeded() { super("Google Drive storage is insufficient"); }
    }

    /** A snapshot of the uploading account's quota, which can change at any time. */
    @Override public void checkUploadCapacity(long bytes, String accessToken) throws IOException, InterruptedException {
        if (bytes < 1 || bytes > MAX_SIZE || accessToken == null || accessToken.isBlank()
                || accessToken.length() > 4096 || accessToken.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid Drive quota check");
        var request = HttpRequest.newBuilder(QUOTA).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + accessToken).GET().build();
        var response = transport.send(request);
        if (response == null || response.status() != 200 || response.body() == null || response.body().length > MAX_REPLY)
            throw new IOException("Drive quota could not be checked");
        try {
            var json = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
            var quota = json.getAsJsonObject("storageQuota");
            if (quota == null) throw new IOException("Drive quota unavailable");
            if (json.has("maxUploadSize") && bytes > quotaNumber(json.get("maxUploadSize").getAsString())) throw new QuotaExceeded();
            if (quota.has("limit")) {
                long limit = quotaNumber(quota.get("limit").getAsString());
                long usage = quotaNumber(quota.get("usage").getAsString());
                if (usage >= limit || bytes > limit - usage) throw new QuotaExceeded();
            }
        } catch (RuntimeException invalid) { throw new IOException("Invalid Drive quota response", invalid); }
    }

    private static long quotaNumber(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("Invalid quota value");
        return Long.parseLong(value);
    }

    /** Creates a new Drive file; never updates an existing object. Runs off the game thread. */
    public ObjectRef upload(Path archive, String folderId, String accessToken) throws IOException, InterruptedException {
        return upload(archive, folderId, accessToken, ignored -> {});
    }

    @Override public ObjectRef upload(Path archive, String folderId, String accessToken, java.util.function.LongConsumer progress) throws IOException, InterruptedException {
        if (progress == null) throw new IllegalArgumentException("Missing progress callback");
        if (folderId == null || !folderId.matches("[A-Za-z0-9_-]{1,200}") || accessToken == null
                || accessToken.isBlank() || accessToken.length() > 4096 || accessToken.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid Drive credentials or folder");
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid archive");
        if (BinaryDelta.isDelta(archive)) BinaryDelta.inspect(archive);
        else SnapshotVerifier.verify(archive);
        long size = Files.size(archive);
        if (size <= 0 || size > MAX_SIZE) throw new IOException("Invalid snapshot size");
        checkUploadCapacity(size, accessToken);
        String expectedHash = hashFile(archive);
        String name = expectedHash + ".zip";
        var metadata = new JsonObject();
        metadata.addProperty("name", name);
        var parents = new JsonArray(); parents.add(folderId); metadata.add("parents", parents);
        var initial = HttpRequest.newBuilder(INITIATE).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Upload-Content-Type", "application/zip").header("X-Upload-Content-Length", Long.toString(size))
                .POST(HttpRequest.BodyPublishers.ofString(metadata.toString())).build();
        Reply response = transport.send(initial);
        if (response.status() != 200) throw new IOException("Drive could not start upload");
        URI session;
        try { session = URI.create(response.headers().firstValue("Location").orElseThrow()); }
        catch (RuntimeException invalid) { throw new IOException("Invalid Drive upload session", invalid); }
        if (!trustedSession(session)) throw new IOException("Untrusted Drive upload session");
        var uploadedHash = digest();
        long offset = 0;
        progress.accept(0);
        Reply finalResponse = null;
        try (var input = Files.newInputStream(archive, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (offset < size) {
                int length = (int) Math.min(CHUNK, size - offset);
                byte[] chunk = input.readNBytes(length);
                if (chunk.length != length) throw new IOException("Snapshot changed during upload");
                uploadedHash.update(chunk);
                long last = offset + length - 1;
                var upload = HttpRequest.newBuilder(session).timeout(Duration.ofMinutes(2))
                        .header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/zip")
                        .header("Content-Range", "bytes " + offset + "-" + last + "/" + size)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(chunk)).build();
                response = transport.send(upload);
                if (last + 1 < size) {
                    if (response.status() != 308 || !response.headers().firstValue("Range").orElse("").equals("bytes=0-" + last))
                        throw new IOException("Drive upload was interrupted");
                } else {
                    if (response.status() != 200 && response.status() != 201) throw new IOException("Drive upload did not finish");
                    finalResponse = response;
                }
                offset += length;
                progress.accept(offset * 100 / size);
            }
            if (input.read() != -1) throw new IOException("Snapshot changed during upload");
        }
        if (!HexFormat.of().formatHex(uploadedHash.digest()).equals(expectedHash)) throw new IOException("Snapshot changed during upload");
        return validateMetadata(finalResponse, folderId, name, expectedHash, size);
    }

    /** Downloads to a new file, validates its SHA-256 and verifies the complete archive. */
    public SnapshotVerifier.Result download(ObjectRef object, Path destination, String accessToken) throws IOException, InterruptedException {
        return download(object, destination, accessToken, ignored -> {});
    }

    @Override public SnapshotVerifier.Result download(ObjectRef object, Path destination, String accessToken, java.util.function.LongConsumer progress) throws IOException, InterruptedException {
        downloadRaw(object, destination, accessToken, progress);
        return SnapshotVerifier.verify(destination);
    }

    @Override public void downloadRaw(ObjectRef object, Path destination, String accessToken) throws IOException, InterruptedException {
        downloadRaw(object, destination, accessToken, ignored -> {});
    }

    @Override public void downloadRaw(ObjectRef object, Path destination, String accessToken, java.util.function.LongConsumer progress) throws IOException, InterruptedException {
        if (progress == null) throw new IllegalArgumentException("Missing progress callback");
        if (object == null || object.fileId() == null || !object.fileId().matches("[A-Za-z0-9_-]{1,200}")
                || object.sha256() == null || !object.sha256().matches("[0-9a-f]{64}") || object.size() <= 0 || object.size() > MAX_SIZE
                || accessToken == null || accessToken.isBlank() || accessToken.length() > 4096 || accessToken.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid Drive download");
        if (downloader == null) throw new IOException("Download transport unavailable");
        DiskSpace.require(destination.toAbsolutePath().getParent(), object.size());
        URI uri = URI.create("https://www.googleapis.com/drive/v3/files/" + object.fileId() + "?alt=media");
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofHours(2))
                .header("Authorization", "Bearer " + accessToken).GET().build();
        try (var output = FileChannel.open(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            progress.accept(0);
            if (downloader.receive(request, output, object.size(), object.sha256(), progress) != 200) throw new IOException("Drive download failed");
            output.force(true);
        }
        if (Files.size(destination) != object.size() || !hashFile(destination).equals(object.sha256()))
            throw new IOException("Downloaded snapshot mismatch");
        progress.accept(100);
    }

    /** Whether an already-authorized account can read the exact Drive object. */
    public boolean canRead(ObjectRef object, String accessToken) throws IOException, InterruptedException {
        if (object == null || object.fileId() == null || !object.fileId().matches("[A-Za-z0-9_-]{1,200}")
                || accessToken == null || accessToken.isBlank() || accessToken.length() > 4096
                || accessToken.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid Drive lookup");
        var request = HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files/" + object.fileId() + "?fields=id"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + accessToken).GET().build();
        Reply reply = transport.send(request);
        if (reply.status() == 403 || reply.status() == 404) return false;
        if (reply.status() != 200) throw new IOException("Drive access check failed");
        try {
            return object.fileId().equals(JsonParser.parseString(new String(reply.body(), StandardCharsets.UTF_8)).getAsJsonObject().get("id").getAsString());
        } catch (RuntimeException invalid) { throw new IOException("Invalid Drive access response", invalid); }
    }

    @Override public boolean canUseBase(ObjectRef object, String folder, String accessToken) throws IOException, InterruptedException {
        if (object == null || object.fileId() == null || !object.fileId().matches("[A-Za-z0-9_-]{1,200}")
                || object.sha256() == null || !object.sha256().matches("[0-9a-f]{64}") || object.size() < 1 || object.size() > MAX_SIZE
                || folder == null || !folder.matches("[A-Za-z0-9_-]{1,200}") || accessToken == null || accessToken.isBlank()
                || accessToken.length() > 4096 || accessToken.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid base lookup");
        var request = HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files/" + object.fileId()
                + "?fields=id,name,size,sha256Checksum,parents,trashed"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + accessToken).GET().build();
        Reply reply = transport.send(request);
        if (reply.status() == 403 || reply.status() == 404) return false;
        if (reply.status() != 200) throw new IOException("Drive base check failed");
        validateMetadata(reply, folder, object.sha256() + ".zip", object.sha256(), object.size());
        return true;
    }

    /** Inspect a small prefix without downloading an entire full snapshot. */
    public boolean isDelta(ObjectRef object, String accessToken) throws IOException, InterruptedException {
        if (object == null || object.fileId() == null || !object.fileId().matches("[A-Za-z0-9_-]{1,200}")
                || accessToken == null || accessToken.isBlank() || accessToken.length() > 4096
                || accessToken.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid Drive lookup");
        var request = HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files/" + object.fileId() + "?alt=media"))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + accessToken).header("Range", "bytes=0-7").GET().build();
        var response = transport.send(request);
        if ((response.status() != 200 && response.status() != 206) || response.body().length < 8)
            throw new IOException("Drive snapshot inspection failed");
        return BinaryDelta.hasMagic(java.util.Arrays.copyOf(response.body(), 8));
    }

    private static ObjectRef validateMetadata(Reply response, String folder, String name, String hash, long size) throws IOException {
        try {
            var json = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
            String id = json.get("id").getAsString();
            boolean parentFound = false;
            for (var item : json.getAsJsonArray("parents")) if (folder.equals(item.getAsString())) parentFound = true;
            if (!id.matches("[A-Za-z0-9_-]{1,200}") || !name.equals(json.get("name").getAsString())
                    || !hash.equals(json.get("sha256Checksum").getAsString()) || size != Long.parseLong(json.get("size").getAsString())
                    || !parentFound || !json.has("trashed") || json.get("trashed").getAsBoolean())
                throw new IOException("Drive object verification failed");
            return new ObjectRef(id, hash, size);
        } catch (RuntimeException invalid) { throw new IOException("Invalid Drive object metadata", invalid); }
    }

    private static boolean trustedSession(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && "www.googleapis.com".equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null && uri.getFragment() == null
                && "/upload/drive/v3/files".equals(uri.getPath()) && uri.getRawQuery() != null;
    }

    private static String hashFile(Path file) throws IOException {
        var hash = digest();
        try (var input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[1024 * 1024]; int read;
            while ((read = input.read(buffer)) != -1) hash.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    private static MessageDigest digest() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new IOException("SHA-256 unavailable", unavailable); }
    }

    private static Transport httpTransport(HttpClient client) {
        return request -> {
            var pending = client.sendAsync(request, info -> new BoundedBody());
            try {
                var response = pending.get(2, TimeUnit.MINUTES);
                return new Reply(response.statusCode(), response.headers(), response.body());
            } catch (TimeoutException | ExecutionException failure) {
                pending.cancel(true); throw new IOException("Drive request failed", failure);
            } catch (InterruptedException interrupted) { pending.cancel(true); throw interrupted; }
        };
    }

    private static Downloader httpDownloader(HttpClient client) {
        return (request, output, size, expectedHash, progress) -> {
            var pending = client.sendAsync(request, info -> info.statusCode() == 200
                    ? new FileBody(output, size, expectedHash, progress) : new RejectedBody());
            try { return pending.get(2, TimeUnit.HOURS).statusCode(); }
            catch (TimeoutException | ExecutionException failure) {
                pending.cancel(true); throw new IOException("Drive download failed", failure);
            } catch (InterruptedException interrupted) { pending.cancel(true); throw interrupted; }
        };
    }

    private static final class RejectedBody implements HttpResponse.BodySubscriber<Void> {
        private final CompletableFuture<Void> result = new CompletableFuture<>();
        @Override public CompletionStage<Void> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { value.cancel(); result.completeExceptionally(new IOException("Drive download rejected")); }
        @Override public void onNext(List<ByteBuffer> ignored) { }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.completeExceptionally(new IOException("Drive download rejected")); }
    }

    private static final class FileBody implements HttpResponse.BodySubscriber<Void> {
        private final CompletableFuture<Void> result = new CompletableFuture<>();
        private final FileChannel output;
        private final long expectedSize;
        private final String expectedHash;
        private final MessageDigest hash;
        private final java.util.function.LongConsumer progress;
        private Flow.Subscription subscription;
        private long count;
        private long lastPercent = -1;
        FileBody(FileChannel output, long expectedSize, String expectedHash, java.util.function.LongConsumer progress) {
            this.output = output; this.expectedSize = expectedSize; this.expectedHash = expectedHash;
            this.progress = progress;
            try { this.hash = digest(); } catch (IOException unavailable) { throw new IllegalStateException(unavailable); }
        }
        @Override public CompletionStage<Void> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            try {
                for (var buffer : buffers) {
                    int size = buffer.remaining();
                    if (size > expectedSize - count) throw new IOException("Drive file exceeds expected size");
                    count += size;
                    hash.update(buffer.asReadOnlyBuffer());
                    while (buffer.hasRemaining()) output.write(buffer);
                }
                long percent = Math.min(99, count * 100 / expectedSize);
                if (percent != lastPercent) { progress.accept(percent); lastPercent = percent; }
                subscription.request(1);
            } catch (IOException | RuntimeException failure) { subscription.cancel(); result.completeExceptionally(failure); }
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() {
            if (count != expectedSize || !HexFormat.of().formatHex(hash.digest()).equals(expectedHash))
                result.completeExceptionally(new IOException("Drive file content mismatch"));
            else result.complete(null);
        }
    }

    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > MAX_REPLY - bytes.size()) { subscription.cancel(); result.completeExceptionally(new IOException("Drive response too large")); return; }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
