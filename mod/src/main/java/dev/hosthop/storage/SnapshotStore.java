package dev.hosthop.storage;

import dev.hosthop.snapshot.SnapshotVerifier;
import java.io.IOException;
import java.nio.file.Path;

/** Storage used by the shared-world lifecycle; operations create new objects only. */
public interface SnapshotStore {
    GoogleDriveSnapshotStore.ObjectRef upload(Path archive, String folderId, String accessToken) throws IOException, InterruptedException;
    SnapshotVerifier.Result download(GoogleDriveSnapshotStore.ObjectRef object, Path destination, String accessToken) throws IOException, InterruptedException;
}
