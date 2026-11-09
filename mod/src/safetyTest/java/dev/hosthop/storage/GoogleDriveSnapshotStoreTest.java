package dev.hosthop.storage;

import dev.hosthop.snapshot.SnapshotWriter;
import java.io.IOException;
import java.net.http.HttpHeaders;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class GoogleDriveSnapshotStoreTest {
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("hosthop-drive-test-");
        var world = root.resolve("world");
        var archive = root.resolve("snapshot.zip");
        var downloaded = root.resolve("downloaded.zip");
        var badDownload = root.resolve("bad-download.zip");
        Files.createDirectory(world);
        var data = new byte[9 * 1024 * 1024];
        new java.util.Random(13).nextBytes(data);
        Files.write(world.resolve("data"), data);
        try {
            SnapshotWriter.create(world, archive);
            long size = Files.size(archive);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)));
            var calls = new AtomicInteger();
            var store = new GoogleDriveSnapshotStore(request -> {
                int index = calls.getAndIncrement();
                check(request.headers().firstValue("Authorization").orElseThrow().equals("Bearer test-token"));
                if (index == 0) return reply(200, Map.of(), "{\"storageQuota\":{\"limit\":\"1000000000\",\"usage\":\"10\"}}");
                if (index == 1) return reply(200, Map.of("Location", List.of("https://www.googleapis.com/upload/drive/v3/files?upload_id=test")), "");
                if (index == 2) {
                    check(request.headers().firstValue("Content-Range").orElseThrow().equals("bytes 0-8388607/" + size));
                    return reply(308, Map.of("Range", List.of("bytes=0-8388607")), "");
                }
                check(index == 3 && request.headers().firstValue("Content-Range").orElseThrow().equals("bytes 8388608-" + (size - 1) + "/" + size));
                return reply(200, Map.of(), "{\"id\":\"file-id\",\"name\":\"" + hash + ".zip\",\"size\":\"" + size + "\",\"sha256Checksum\":\"" + hash + "\",\"parents\":[\"folder\"],\"trashed\":false}");
            });
            var progress = new java.util.ArrayList<Long>();
            var uploaded = store.upload(archive, "folder", "test-token", progress::add);
            check(progress.size() == 3 && progress.get(0) == 0 && progress.get(1) > 0 && progress.get(1) < 100 && progress.get(2) == 100);
            check(calls.get() == 4 && uploaded.fileId().equals("file-id") && uploaded.sha256().equals(hash) && uploaded.size() == size);
            String metadata = "{\"id\":\"file-id\",\"name\":\"" + hash + ".zip\",\"size\":\"" + size + "\",\"sha256Checksum\":\"" + hash + "\",\"parents\":[\"folder\"],\"trashed\":false}";
            var resumeCalls = new AtomicInteger();
            var resume = new GoogleDriveSnapshotStore(request -> {
                int step = resumeCalls.getAndIncrement();
                if (step == 0) return reply(200, Map.of(), "{\"storageQuota\":{}}");
                if (step == 1) return reply(200, Map.of("Location", List.of("https://www.googleapis.com/upload/drive/v3/files?upload_id=test")), "");
                if (step == 2) throw new IOException("response lost");
                if (step == 3) {
                    check(request.headers().firstValue("Content-Range").orElseThrow().equals("bytes */" + size));
                    return reply(308, Map.of("Range", List.of("bytes=0-4194303")), "");
                }
                if (step == 4) {
                    check(request.headers().firstValue("Content-Range").orElseThrow().equals("bytes 4194304-" + (size - 1) + "/" + size));
                    throw new IOException("final response lost");
                }
                check(step == 5 && request.headers().firstValue("Content-Range").orElseThrow().equals("bytes */" + size));
                return reply(200, Map.of(), metadata);
            });
            check(resume.upload(archive, "folder", "test-token").equals(uploaded) && resumeCalls.get() == 6);
            var invalidPositionCalls = new AtomicInteger();
            var invalidPosition = new GoogleDriveSnapshotStore(request -> {
                int step = invalidPositionCalls.getAndIncrement();
                if (step == 0) return reply(200, Map.of(), "{\"storageQuota\":{}}");
                if (step == 1) return reply(200, Map.of("Location", List.of("https://www.googleapis.com/upload/drive/v3/files?upload_id=test")), "");
                if (step == 2) throw new IOException("response lost");
                return reply(308, Map.of("Range", List.of("bytes=0-999999999")), "");
            });
            try { invalidPosition.upload(archive, "folder", "test-token"); throw new AssertionError("invalid resume position accepted"); }
            catch (IOException expected) { }
            var permission = new GoogleDriveSnapshotStore(request -> reply(404, Map.of(), ""));
            check(!permission.canRead(uploaded, "test-token"));
            check(!permission.canUseBase(uploaded, "folder", "test-token"));
            var baseMetadata = new GoogleDriveSnapshotStore(request -> reply(200, Map.of(), "{\"id\":\"file-id\",\"name\":\"" + hash
                    + ".zip\",\"size\":\"" + size + "\",\"sha256Checksum\":\"" + hash + "\",\"parents\":[\"folder\"],\"trashed\":false}"));
            check(baseMetadata.canUseBase(uploaded, "folder", "test-token"));
            try { baseMetadata.canUseBase(uploaded, "wrong-folder", "test-token"); throw new AssertionError("wrong base folder accepted"); }
            catch (IOException expected) { }
            var inspection = new GoogleDriveSnapshotStore(request -> {
                if (request.headers().firstValue("Range").isPresent()) {
                    check(request.headers().firstValue("Range").orElseThrow().equals("bytes=0-7"));
                    return reply(206, Map.of(), "HHDIFF01");
                }
                return reply(200, Map.of(), "{\"id\":\"file-id\"}");
            });
            check(inspection.canRead(uploaded, "test-token") && inspection.isDelta(uploaded, "test-token"));
            var fullPrefix = new GoogleDriveSnapshotStore(request -> reply(206, Map.of(), "PK\u0003\u0004abcd"));
            check(!fullPrefix.isDelta(uploaded, "test-token"));
            var blockedCalls = new AtomicInteger();
            var full = new GoogleDriveSnapshotStore(request -> {
                blockedCalls.incrementAndGet();
                return reply(200, Map.of(), "{\"storageQuota\":{\"limit\":\"100\",\"usage\":\"99\"}}");
            });
            try { full.upload(archive, "folder", "test-token"); throw new AssertionError("Insufficient quota accepted"); }
            catch (GoogleDriveSnapshotStore.QuotaExceeded expected) { }
            check(blockedCalls.get() == 1);
            var unlimited = new GoogleDriveSnapshotStore(request -> reply(200, Map.of(), "{\"storageQuota\":{}}"));
            unlimited.checkUploadCapacity(size, "test-token");
            var downloader = new GoogleDriveSnapshotStore(request -> { throw new AssertionError(); }, (request, output, expectedSize, expectedHash, progressUpdate) -> {
                check(request.uri().toString().equals("https://www.googleapis.com/drive/v3/files/file-id?alt=media"));
                var bytes = java.nio.ByteBuffer.wrap(Files.readAllBytes(archive));
                while (bytes.hasRemaining()) output.write(bytes);
                return 200;
            });
            var downloadProgress = new java.util.ArrayList<Long>();
            check(downloader.download(uploaded, downloaded, "test-token", downloadProgress::add).files() == 1);
            check(downloadProgress.equals(java.util.List.of(0L, 100L)));
            try { downloader.download(uploaded, downloaded, "test-token"); throw new AssertionError("download overwritten"); }
            catch (java.nio.file.FileAlreadyExistsException expected) { }
            var corruptDownloader = new GoogleDriveSnapshotStore(request -> { throw new AssertionError(); }, (request, output, expectedSize, expectedHash, progressUpdate) -> {
                output.write(java.nio.ByteBuffer.wrap(new byte[] { 1, 2, 3 })); return 200;
            });
            try { corruptDownloader.download(uploaded, badDownload, "test-token"); throw new AssertionError("corrupt download accepted"); }
            catch (IOException expected) { }
            var hostile = new GoogleDriveSnapshotStore(request -> request.uri().getPath().endsWith("/about")
                    ? reply(200, Map.of(), "{\"storageQuota\":{}}")
                    : reply(200, Map.of("Location", List.of("https://attacker.invalid/upload/drive/v3/files?upload_id=test")), ""));
            try { hostile.upload(archive, "folder", "test-token"); throw new AssertionError("untrusted session accepted"); }
            catch (IOException expected) { }
            var invalidHash = new GoogleDriveSnapshotStore(new GoogleDriveSnapshotStore.Transport() {
                int step;
                @Override public GoogleDriveSnapshotStore.Reply send(java.net.http.HttpRequest request) {
                    if (step++ == 0) return reply(200, Map.of(), "{\"storageQuota\":{}}");
                    if (step == 2) return reply(200, Map.of("Location", List.of("https://www.googleapis.com/upload/drive/v3/files?upload_id=test")), "");
                    if (step == 3) return reply(308, Map.of("Range", List.of("bytes=0-8388607")), "");
                    return reply(200, Map.of(), "{\"id\":\"file-id\",\"name\":\"" + hash + ".zip\",\"size\":\"" + size + "\",\"sha256Checksum\":\"wrong\",\"parents\":[\"folder\"]}");
                }
            });
            try { invalidHash.upload(archive, "folder", "test-token"); throw new AssertionError("wrong remote digest accepted"); }
            catch (IOException expected) { }
            System.out.println("Google Drive snapshot transfer checks passed");
        } finally {
            Files.deleteIfExists(downloaded);
            Files.deleteIfExists(badDownload);
            Files.deleteIfExists(archive);
            Files.deleteIfExists(world.resolve("data"));
            Files.delete(world);
            Files.delete(root);
        }
    }

    private static GoogleDriveSnapshotStore.Reply reply(int status, Map<String, List<String>> headers, String body) {
        return new GoogleDriveSnapshotStore.Reply(status, HttpHeaders.of(headers, (a, b) -> true), body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
