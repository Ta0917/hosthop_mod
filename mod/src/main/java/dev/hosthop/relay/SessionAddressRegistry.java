package dev.hosthop.relay;

import java.util.Optional;

/**
 * Tracks an address for one local relay session. The caller must bind every
 * callback to the token created for that exact session. This is not a lease,
 * a distributed lock, or verification that a remote endpoint is reachable.
 */
public final class SessionAddressRegistry {
    public static final class Token {
        private Token() {}
    }

    private Token current;
    private String address;

    /** Replaces any prior local session and immediately removes its address. */
    public synchronized Token begin() {
        current = new Token();
        address = null;
        return current;
    }

    /** A late assignment from a replaced or closed session is ignored. */
    public synchronized boolean publish(Token token, String value) {
        if (token == null || token != current) return false;
        if (value == null || value.isBlank() || value.length() > 255 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid relay address");
        }
        address = value;
        return true;
    }

    /** A stale failure or close event must not invalidate a newer session. */
    public synchronized boolean end(Token token) {
        if (token == null || token != current) return false;
        current = null;
        address = null;
        return true;
    }

    public synchronized Optional<String> address() {
        return Optional.ofNullable(address);
    }
}
