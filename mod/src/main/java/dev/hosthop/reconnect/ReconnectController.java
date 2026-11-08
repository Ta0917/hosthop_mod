package dev.hosthop.reconnect;

/**
 * Retry policy only. Does not authenticate endpoints, connect Minecraft, observe
 * disconnect reasons or persist state; an adapter must do all of those things.
 */
public final class ReconnectController {
    public enum State { IDLE, WAITING, CONNECTING, CONNECTED, STOPPED }
    public enum Failure { TRANSIENT, USER_CANCELLED, KICKED, AUTHENTICATION, INCOMPATIBLE }
    public static final class Session { private Session() {} }
    public static final class Attempt { private Attempt() {} }
    public record Destination(String world, long epoch, String address) {}

    private static final int MAX_ATTEMPTS = 5;
    private static final long BASE_DELAY_NANOS = 1_000_000_000L;
    private Session session;
    private Attempt attempt;
    private State state = State.IDLE;
    private String world;
    private long lastEpoch;
    private long lastTime;
    private long nextTime;
    private int attempts;

    /** Only start for a user-selected HostHop world after an eligible disconnect. */
    public synchronized Session start(String world, long previousEpoch, long nowNanos) {
        if (state != State.IDLE && state != State.STOPPED) throw new IllegalStateException("Session already active");
        if (world == null || world.isBlank() || previousEpoch < 1) throw new IllegalArgumentException("Invalid session");
        this.world = world;
        lastEpoch = previousEpoch;
        lastTime = nextTime = nowNanos;
        attempts = 0;
        attempt = null;
        session = new Session();
        state = State.WAITING;
        return session;
    }

    /** Caller must authenticate the current authority result and its freshness. */
    public synchronized Attempt connect(Session session, Destination destination, long nowNanos) {
        if (session == null || session != this.session || state != State.WAITING || !timeValid(nowNanos)) return null;
        if (nowNanos - nextTime < 0 || destination == null || !world.equals(destination.world())
                || destination.epoch() < lastEpoch || destination.address() == null
                || destination.address().isBlank() || destination.address().length() > 255) return null;
        lastEpoch = destination.epoch();
        attempts++;
        attempt = new Attempt();
        state = State.CONNECTING;
        return attempt;
    }

    public synchronized boolean connected(Session session, Attempt attempt) {
        if (!current(session, attempt)) return false;
        this.attempt = null;
        state = State.CONNECTED;
        return true;
    }

    public synchronized boolean failed(Session session, Attempt attempt, Failure reason, long nowNanos) {
        if (!current(session, attempt) || reason == null) return false;
        this.attempt = null;
        if (!timeValid(nowNanos)) return false;
        if (reason != Failure.TRANSIENT || attempts >= MAX_ATTEMPTS) {
            state = State.STOPPED;
        } else {
            nextTime = nowNanos + (BASE_DELAY_NANOS << (attempts - 1));
            state = State.WAITING;
        }
        return true;
    }

    public synchronized boolean cancel(Session session) {
        if (session == null || session != this.session || state == State.STOPPED || state == State.IDLE) return false;
        attempt = null;
        state = State.STOPPED;
        return true;
    }

    public synchronized State state() { return state; }

    private boolean current(Session session, Attempt attempt) {
        return session != null && session == this.session && attempt != null && attempt == this.attempt && state == State.CONNECTING;
    }

    private boolean timeValid(long now) {
        if (now - lastTime < 0) { state = State.STOPPED; attempt = null; return false; }
        lastTime = now;
        return true;
    }
}
