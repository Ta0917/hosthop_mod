package dev.hosthop.client;

import com.mojang.logging.LogUtils;
import dev.hosthop.config.Invitation;
import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.snapshot.SnapshotPrefetch;
import dev.hosthop.storage.DriveCredentialManager;
import dev.hosthop.storage.GoogleDriveAuthorization;
import dev.hosthop.storage.GoogleDriveSnapshotStore;
import dev.hosthop.storage.WindowsSecretStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

/**
 * While this client is a guest, quietly keeps the current shared snapshot (and the base it depends on)
 * in the local cache, so that becoming host later only has to download the newest small delta.
 * Downloads are throttled and verified, and never publish anything.
 */
final class GuestPrefetch {
    private static final Object LOCK = new Object();
    private static GuestPrefetch current;
    /** About 8 Mbps: leaves room for play on a 20 Mbps line. */
    private static final long RATE = 1_000_000L;
    private static final int KEEP = 4;

    private final Invitation invitation;
    private final LeaseAuthorityClient authority;
    private final Path sessions;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "hosthop-prefetch"); thread.setDaemon(true); return thread;
    });
    private ScheduledFuture<?> task;
    private GoogleDriveSnapshotStore.ObjectRef last;
    private int failures;

    private GuestPrefetch(Invitation invitation, LeaseAuthorityClient authority, Path sessions) {
        this.invitation = invitation; this.authority = authority; this.sessions = sessions;
    }

    static void start(Invitation invitation, LeaseAuthorityClient authority) {
        Path sessions = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize().resolve("hosthop").resolve("sessions");
        synchronized (LOCK) {
            if (current != null) return;
            var value = new GuestPrefetch(invitation, authority, sessions);
            current = value;
            value.task = value.executor.scheduleWithFixedDelay(value::tick, 5, 60, TimeUnit.SECONDS);
        }
    }

    static void stop() {
        GuestPrefetch value;
        synchronized (LOCK) { value = current; current = null; }
        if (value != null) { if (value.task != null) value.task.cancel(true); value.executor.shutdownNow(); }
    }

    private void tick() {
        try {
            if (HostWorldRuntime.active() || failures >= 5) return;
            var snapshot = authority.snapshot(invitation.world());
            if (snapshot == null || snapshot.object().equals(last)) return;
            if (snapshot.object().size() > 0 && Files.getFileStore(sessions.getParent()).getUsableSpace() < snapshot.object().size() * 4) return;
            var vault = new WindowsSecretStore(FMLPaths.CONFIGDIR.get().resolve("hosthop").resolve("credentials.dat"));
            var manager = new DriveCredentialManager(vault, new GoogleDriveAuthorization(invitation.oauthClientId(), invitation.oauthClientSecret()));
            String token = manager.access().accessToken();
            SnapshotPrefetch.warm(sessions, new GoogleDriveSnapshotStore(RATE), snapshot.object(), token);
            last = snapshot.object(); failures = 0;
            prune();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            failures++;
            LogUtils.getLogger().warn("HostHop prefetch skipped ({})", failure.getClass().getSimpleName());
        }
    }

    /** Only directories created by this class are removed; normal session directories are never touched. */
    private void prune() throws IOException {
        var old = new ArrayList<Path>();
        try (var children = Files.newDirectoryStream(sessions, "prefetch-*")) {
            for (Path child : children) if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) old.add(child);
        }
        old.sort(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed());
        for (int i = KEEP; i < old.size(); i++) {
            try (var files = Files.newDirectoryStream(old.get(i))) {
                for (Path file : files) if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) Files.delete(file);
            }
            Files.delete(old.get(i));
        }
    }
}
