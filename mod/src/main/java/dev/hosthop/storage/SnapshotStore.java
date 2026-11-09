package dev.hosthop.storage;

import dev.hosthop.snapshot.SnapshotVerifier;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.LongConsumer;

/** Storage used by the shared-world lifecycle; operations create new objects only. */
public interface SnapshotStore {
    /** Check capacity against an estimated upload size, if supported. */
    default void checkUploadCapacity(long bytes, String accessToken) throws IOException, InterruptedException {}
    /** A delta may reference a base only after the store validates its remote metadata. */
    default boolean canUseBase(GoogleDriveSnapshotStore.ObjectRef base, String folderId, String accessToken) throws IOException, InterruptedException { return false; }
    GoogleDriveSnapshotStore.ObjectRef upload(Path archive, String folderId, String accessToken) throws IOException, InterruptedException;
    default GoogleDriveSnapshotStore.ObjectRef upload(Path archive, String folderId, String accessToken, LongConsumer progress) throws IOException, InterruptedException {
        var object = upload(archive, folderId, accessToken);
        progress.accept(100);
        return object;
    }
    SnapshotVerifier.Result download(GoogleDriveSnapshotStore.ObjectRef object, Path destination, String accessToken) throws IOException, InterruptedException;
    default SnapshotVerifier.Result download(GoogleDriveSnapshotStore.ObjectRef object, Path destination, String accessToken, LongConsumer progress) throws IOException, InterruptedException {
        var result = download(object, destination, accessToken);
        progress.accept(100);
        return result;
    }
    /** Download an authenticated full snapshot or delta container without interpreting its contents. */
    default void downloadRaw(GoogleDriveSnapshotStore.ObjectRef object, Path destination, String accessToken) throws IOException, InterruptedException {
        download(object, destination, accessToken);
    }
    default void downloadRaw(GoogleDriveSnapshotStore.ObjectRef object, Path destination, String accessToken, LongConsumer progress) throws IOException, InterruptedException {
        downloadRaw(object, destination, accessToken);
        progress.accept(100);
    }
}
