package dev.hosthop.lease;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.function.LongSupplier;

/** Local preparation only: no network operation is performed here. */
public final class PublicationPreparation {
    private PublicationPreparation() {}

    /**
     * Records the exact operation before the caller sends it. Existing evidence
     * can only be reused for the same operation. Failure leaves the gate stopped.
     * The caller must discover this journal on startup, authenticate responses,
     * and ensure filesystem and parent-directory durability before sending.
     */
    public static boolean prepare(LocalHostGate gate, LocalHostGate.Attempt attempt, Path journal,
                                  PublicationRecord operation, LongSupplier nanoClock, LongSupplier wallClock) throws IOException {
        if (gate == null || journal == null || nanoClock == null || wallClock == null)
            throw new IllegalArgumentException("Missing preparation argument");
        if (!gate.mayPublish(attempt, operation, nanoClock.getAsLong(), wallClock.getAsLong())) return false;
        try {
            try {
                PendingPublicationJournal.create(journal, operation);
            } catch (FileAlreadyExistsException exists) {
                if (!operation.equals(PendingPublicationJournal.read(journal)))
                    throw new IOException("Pending publication differs from journal");
            }
            return gate.mayPublish(attempt, operation, nanoClock.getAsLong(), wallClock.getAsLong());
        } catch (IOException | RuntimeException failure) {
            gate.requireStop(attempt);
            throw failure;
        }
    }
}
