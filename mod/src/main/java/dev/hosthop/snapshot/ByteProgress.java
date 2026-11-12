package dev.hosthop.snapshot;

import java.util.function.DoubleConsumer;

/** Reports the fraction of bytes processed, at most every 8 MiB, so long file passes can show progress. */
final class ByteProgress {
    private static final long STEP = 8L * 1024 * 1024;
    private final DoubleConsumer sink;
    private final long total;
    private long done, reported;

    ByteProgress(DoubleConsumer sink, long total) {
        this.sink = sink == null ? ignored -> {} : sink;
        this.total = Math.max(1, total);
    }

    synchronized void add(long bytes) {
        done += bytes;
        if (done - reported >= STEP) { reported = done; sink.accept(Math.min(1.0, (double) done / total)); }
    }

    void finish() { sink.accept(1.0); }

    /** Maps a stage's own 0..1 onto part of a larger 0..1. */
    static DoubleConsumer range(DoubleConsumer sink, double from, double to) {
        return sink == null ? ignored -> {} : fraction -> sink.accept(from + (to - from) * Math.max(0, Math.min(1, fraction)));
    }
}
