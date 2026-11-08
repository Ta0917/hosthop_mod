package dev.hosthop.lease;

/** An operation and its expected committed result, not proof of authenticity. */
public record PublicationRecord(String worldId, String operationId, String owner, String leaseId,
                                long epoch, long previousGeneration, String digest, long resultGeneration) {
    public PublicationRecord {
        if (worldId == null || worldId.isBlank() || operationId == null || operationId.isBlank()
                || owner == null || owner.isBlank() || leaseId == null || leaseId.isBlank()
                || digest == null || digest.isBlank() || epoch < 1 || previousGeneration < 0
                || previousGeneration == Long.MAX_VALUE || resultGeneration != previousGeneration + 1) {
            throw new IllegalArgumentException("Invalid publication record");
        }
    }
}
