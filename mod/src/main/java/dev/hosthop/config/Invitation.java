package dev.hosthop.config;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** A per-member invitation contains credentials and must be handled as a secret. */
public final class Invitation {
    private static final String[] KEYS = { "authority", "world", "principal", "member_token", "oauth_client_id", "oauth_client_secret", "drive_folder" };
    private final Map<String, String> fields;

    private Invitation(Map<String, String> fields) { this.fields = Map.copyOf(fields); }
    public String authority() { return fields.get("authority"); }
    public String world() { return fields.get("world"); }
    public String principal() { return fields.get("principal"); }
    public String memberToken() { return fields.get("member_token"); }
    public String oauthClientId() { return fields.get("oauth_client_id"); }
    public String oauthClientSecret() { return fields.get("oauth_client_secret"); }
    public String folder() { return fields.get("drive_folder"); }
    public Map<String, String> secrets() { return fields; }
    @Override public String toString() { return "HostHop invitation [redacted]"; }

    public static Invitation fromSecrets(Map<String, String> data) throws IOException {
        if (data == null) throw new IOException("Missing invitation");
        var copy = new HashMap<String, String>();
        for (String key : KEYS) {
            String value = data.get(key);
            if (value == null) throw new IOException("Incomplete invitation");
            copy.put(key, value);
        }
        validate(copy);
        return new Invitation(copy);
    }

    public static Invitation decode(String code) throws IOException {
        if (code == null || code.length() > 8192) throw new IOException("Invalid invitation");
        code = code.strip();
        if (!code.startsWith("hosthop1:")) throw new IOException("Invalid invitation");
        byte[] decoded;
        try { decoded = Base64.getUrlDecoder().decode(code.substring(9)); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid invitation", invalid); }
        if (decoded.length > 6144) throw new IOException("Invitation too large");
        var fields = new HashMap<String, String>();
        try (var reader = new JsonReader(new StringReader(new String(decoded, StandardCharsets.UTF_8)))) {
            reader.setLenient(false);
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!java.util.Arrays.asList(KEYS).contains(key) || fields.containsKey(key) || reader.peek() != JsonToken.STRING)
                    throw new IOException("Invalid invitation field");
                fields.put(key, reader.nextString());
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || fields.size() != KEYS.length) throw new IOException("Incomplete invitation");
        } catch (IllegalStateException invalid) { throw new IOException("Invalid invitation", invalid); }
        validate(fields);
        return new Invitation(fields);
    }

    public String encode() {
        var json = new JsonObject();
        for (String key : KEYS) json.addProperty(key, fields.get(key));
        return "hosthop1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void validate(Map<String, String> fields) throws IOException {
        try {
            URI authority = URI.create(fields.get("authority"));
            if (!"https".equalsIgnoreCase(authority.getScheme()) || authority.getHost() == null || authority.getUserInfo() != null
                    || authority.getRawQuery() != null || authority.getRawFragment() != null
                    || !(authority.getPath().isEmpty() || authority.getPath().equals("/"))) throw new IOException("Invalid authority");
            if (!fields.get("world").matches("[A-Za-z0-9_-]{1,80}") || !fields.get("principal").matches("[A-Za-z0-9_-]{1,80}")
                    || !fields.get("member_token").matches("[A-Za-z0-9_-]{43}") || !fields.get("drive_folder").matches("[A-Za-z0-9_-]{1,200}")
                    || !fields.get("oauth_client_id").matches("[A-Za-z0-9._-]{1,512}")
                    || !fields.get("oauth_client_secret").matches("[A-Za-z0-9_-]{1,512}")) throw new IOException("Invalid invitation fields");
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid invitation", invalid); }
    }
}
