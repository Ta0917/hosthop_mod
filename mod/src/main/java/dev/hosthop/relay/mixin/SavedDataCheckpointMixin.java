package dev.hosthop.relay.mixin;

import dev.hosthop.snapshot.LiveWorldCopy;
import dev.hosthop.world.CheckpointSave;
import dev.hosthop.world.CheckpointSavedData;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SavedDataStorage.class)
abstract class SavedDataCheckpointMixin implements CheckpointSavedData {
    @Shadow private CompletableFuture<?> pendingWriteFuture;
    @Shadow public abstract CompletableFuture<?> scheduleSave();
    @Shadow private Path getDataFile(Identifier id) { throw new AssertionError(); }
    @Unique private final Set<Path> hosthop$pending = new HashSet<>();

    @Inject(method = "collectDirtyTagsToSave", at = @At("RETURN"))
    private void hosthop$trackQueuedPaths(CallbackInfoReturnable<Map<SavedDataType<?>, CompoundTag>> callback) {
        if (pendingWriteFuture.isDone()) hosthop$pending.clear();
        for (var type : callback.getReturnValue().keySet()) {
            hosthop$pending.add(getDataFile(type.id()).toAbsolutePath().normalize());
        }
    }

    @Inject(method = "saveAndJoin", at = @At("HEAD"), cancellable = true)
    private void hosthop$deferCheckpointWrites(CallbackInfo callback) {
        if (CheckpointSave.deferringWrites()) {
            scheduleSave();
            callback.cancel();
        }
    }

    @Override public Set<Path> hosthop$pendingPaths() {
        return Set.copyOf(hosthop$pending);
    }

    @Override public CompletableFuture<Void> hosthop$seal(LiveWorldCopy snapshot, Set<Path> paths) {
        var writes = pendingWriteFuture;
        var sealed = writes.thenRunAsync(() -> {
            try {
                snapshot.sealSavedData(paths);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, Util.ioPool());
        // Later saves to these paths wait behind the read. A failed backup must leave the
        // original write chain's result unchanged so Minecraft can continue saving normally.
        pendingWriteFuture = sealed.handle((ignored, failure) -> null).thenCompose(ignored -> writes);
        return sealed;
    }
}
