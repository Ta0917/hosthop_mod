package dev.hosthop.storage;

import dev.hosthop.snapshot.SnapshotVerifier;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.LongConsumer;

/** Storage used by the shared-world lifecycle; operations create new objects only. */
public interface SnapshotStore {
    /** Check capacity against an estimated upload size, if supported. */
    default void checkUploadCapacity(long bytes, String accessToken) throws IOException, InterruptedException {}
    GoogleDriveSnapshotStore.ObjectRef upload(Path archive, String folderId, String accessToken) throws IOException, InterruptedException;
    default GoogleDriveSnapshotStore.ObjectRef upload(Path archive, String folderId, String accessToken, LongConsumer progress) throws IOException, InterruptedException {
        var object = upload(archive, folderId, accessToken);
        progress.accept(100);
        return object;
    }
    SnapshotVerifier.Result download(GoogleDriveSnapshotStore.ObjectRef object, Path destination, String accessToken) throws IOException, InterruptedException;
}
