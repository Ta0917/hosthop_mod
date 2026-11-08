package dev.hosthop.lease;

/** An operation and its expected committed result, not proof of authenticity. */
public record PublicationRecord(String worldId, String operationId, String owner, String leaseId,
                                long epoch, long previousGeneration, String digest, String objectId,
                                long objectSize, long resultGeneration) {
    public PublicationRecord {
        if (worldId == null || worldId.isBlank() || operationId == null || operationId.isBlank()
                || owner == null || owner.isBlank() || leaseId == null || leaseId.isBlank()
                || digest == null || digest.isBlank() || objectId == null || !objectId.matches("[A-Za-z0-9_-]{1,200}")
                || objectSize < 1 || objectSize > 68_719_476_736L || epoch < 1 || previousGeneration < 0
                || previousGeneration == Long.MAX_VALUE || resultGeneration != previousGeneration + 1) {
            throw new IllegalArgumentException("Invalid publication record");
        }
    }
}
