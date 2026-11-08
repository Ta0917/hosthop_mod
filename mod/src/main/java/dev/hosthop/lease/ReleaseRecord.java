package dev.hosthop.lease;

/** Contents of an authority's release record; transport authentication is external. */
public record ReleaseRecord(String worldId, String owner, String leaseId, long epoch,
                            String operationId, long resultGeneration) {
    public boolean matches(PublicationRecord publication) {
        return publication != null && worldId != null && worldId.equals(publication.worldId())
                && owner != null && owner.equals(publication.owner())
                && leaseId != null && leaseId.equals(publication.leaseId())
                && epoch == publication.epoch() && operationId != null
                && operationId.equals(publication.operationId()) && resultGeneration == publication.resultGeneration();
    }
}
