package dev.hosthop.relay;

import net.neoforged.fml.ModList;

/** Version-scoped callbacks; listener methods must enqueue work and return promptly. */
public final class E4mcRelayBridge {
    public interface Listener {
        void opened(Object token);
        void assigned(Object token, String address);
        void closed(Object token);
    }
    private static Listener listener;
    private static Object session, token;
    private E4mcRelayBridge() {}

    public static synchronized void install(Listener value) {
        if (listener != null) throw new IllegalStateException("Relay listener already installed");
        if (value == null) throw new IllegalArgumentException("Missing listener");
        listener = value; session = null; token = null;
    }
    public static synchronized void uninstall(Listener value) {
        if (listener == value) { listener = null; session = null; token = null; }
    }
    public static synchronized void begin(Object source) {
        if (listener == null || source == null || !supported()) return;
        if (token != null) listener.closed(token);
        session = source; token = new Object(); listener.opened(token);
    }
    public static synchronized void assigned(Object source, String address) {
        if (listener == null || session != source || token == null || !RelayAddress.valid(address)) return;
        listener.assigned(token, address);
    }
    public static synchronized void end(Object source) {
        if (listener == null || session != source || token == null) return;
        Object closing = token; session = null; token = null; listener.closed(closing);
    }
    public static boolean supported() {
        if (!net.neoforged.fml.loading.FMLEnvironment.isProduction() && Boolean.getBoolean("hosthop.fakeRelaySmoke")) return true;
        return ModList.get().getModContainerById("e4mc")
                .map(container -> container.getModInfo().getVersion().toString().equals("6.2.3")).orElse(false);
    }

    public static Object outer(Object object, String fieldName) throws ReflectiveOperationException {
        var field = object.getClass().getDeclaredField(fieldName);
        if (!field.trySetAccessible()) throw new IllegalAccessException("Cannot access e4mc session");
        return field.get(object);
    }
}
