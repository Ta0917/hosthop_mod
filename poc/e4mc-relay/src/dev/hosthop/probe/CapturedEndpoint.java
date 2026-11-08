package dev.hosthop.probe;

import dev.hosthop.relay.SessionAddressRegistry;

/** Experiment-only memory, never a production host registry. */
public final class CapturedEndpoint {
    private static final SessionAddressRegistry REGISTRY = new SessionAddressRegistry();
    private static Object session;
    private static SessionAddressRegistry.Token token;
    private CapturedEndpoint() {}

    public static synchronized void begin(Object newSession) {
        session = newSession;
        token = REGISTRY.begin();
    }

    public static synchronized boolean publish(Object sourceSession, String address) {
        return sourceSession == session && REGISTRY.publish(token, address);
    }

    public static synchronized boolean end(Object sourceSession) {
        if (sourceSession == null || sourceSession != session) return false;
        boolean ended = REGISTRY.end(token);
        session = null;
        token = null;
        return ended;
    }

    public static synchronized String address() {
        return REGISTRY.address().orElse(null);
    }

    public static Object outer(Object object, String fieldName) throws ReflectiveOperationException {
        var field = object.getClass().getDeclaredField(fieldName);
        if (!field.trySetAccessible()) throw new IllegalStateException("Cannot inspect e4mc callback owner");
        return field.get(object);
    }
}
