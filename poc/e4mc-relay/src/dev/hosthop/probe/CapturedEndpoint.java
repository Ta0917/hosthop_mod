package dev.hosthop.probe;

/** Experiment-only memory, never a production host registry. */
public final class CapturedEndpoint {
    public static volatile String address;
    private CapturedEndpoint() {}
}
