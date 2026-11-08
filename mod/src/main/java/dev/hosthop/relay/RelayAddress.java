package dev.hosthop.relay;

/** Conservative e4mc hostname validation; does not authenticate the server. */
public final class RelayAddress {
    private static final java.util.regex.Pattern DOMAIN = java.util.regex.Pattern.compile(
            "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*\\.e4mc\\.link");
    private RelayAddress() {}
    public static boolean valid(String value) { return value != null && value.length() <= 253 && DOMAIN.matcher(value).matches(); }
}
