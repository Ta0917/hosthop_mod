package dev.hosthop.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hosthop.snapshot.SnapshotVerifier;
import dev.hosthop.snapshot.DiskSpace;
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
    public record ObjectRef(String fileId, String sha256, long size) {}
    record Reply(int status, HttpHeaders headers, byte[] body) {}
    @FunctionalInterface interface Transport { Reply send(HttpRequest request) throws IOException, InterruptedException; }
    @FunctionalInterface interface Downloader { int receive(HttpRequest request, FileChannel output, long size, String digest) throws IOException, InterruptedException; }
    private final Transport transport;
    private final Downloader downloader;

    public GoogleDriveSnapshotStore() {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        this.transport = httpTransport(client);
        this.downloader = httpDownloader(client);
    }
    GoogleDriveSnapshotStore(Transport transport) { this(transport, null); }
    GoogleDriveSnapshotStore(Transport transport, Downloader downloader) { this.transport = transport; this.downloader = downloader; }

    /** Creates a new Drive file; never updates an existing object. Runs off the game thread. */
    public ObjectRef upload(Path archive, String folderId, String accessToken) throws IOException, InterruptedException {
        if (folderId == null || !folderId.matches("[A-Za-z0-9_-]{1,200}") || accessToken == null
                || accessToken.isBlank() || accessToken.length() > 4096 || accessToken.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid Drive credentials or folder");
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid archive");
        SnapshotVerifier.verify(archive);
        long size = Files.size(archive);
        if (size <= 0 || size > MAX_SIZE) throw new IOException("Invalid snapshot size");
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
            }
            if (input.read() != -1) throw new IOException("Snapshot changed during upload");
        }
        if (!HexFormat.of().formatHex(uploadedHash.digest()).equals(expectedHash)) throw new IOException("Snapshot changed during upload");
        return validateMetadata(finalResponse, folderId, name, expectedHash, size);
    }

    /** Downloads to a new file, validates its SHA-256 and verifies the complete archive. */
    public SnapshotVerifier.Result download(ObjectRef object, Path destination, String accessToken) throws IOException, InterruptedException {
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
            if (downloader.receive(request, output, object.size(), object.sha256()) != 200) throw new IOException("Drive download failed");
            output.force(true);
        }
        if (Files.size(destination) != object.size() || !hashFile(destination).equals(object.sha256()))
            throw new IOException("Downloaded snapshot mismatch");
        return SnapshotVerifier.verify(destination);
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
        return (request, output, size, expectedHash) -> {
            var pending = client.sendAsync(request, info -> info.statusCode() == 200
                    ? new FileBody(output, size, expectedHash) : new RejectedBody());
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
        private Flow.Subscription subscription;
        private long count;
        FileBody(FileChannel output, long expectedSize, String expectedHash) {
            this.output = output; this.expectedSize = expectedSize; this.expectedHash = expectedHash;
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
                subscription.request(1);
            } catch (IOException failure) { subscription.cancel(); result.completeExceptionally(failure); }
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
