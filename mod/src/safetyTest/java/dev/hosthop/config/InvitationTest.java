package dev.hosthop.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

public final class InvitationTest {
    public static void main(String[] args) throws Exception {
        var fields = Map.of("authority", "https://service.example", "world", "world", "principal", "owner",
                "member_token", "a".repeat(43), "oauth_client_id", "client.apps.googleusercontent.com",
                "oauth_client_secret", "client-secret", "drive_folder", "folder");
        var invite = Invitation.fromSecrets(fields);
        var copy = Invitation.decode(invite.encode());
        check(copy.secrets().equals(fields) && !copy.toString().contains("client-secret"));
        check(Invitation.decode(" \r\n" + invite.encode() + "\r\n ").secrets().equals(fields));
        var invalid = new java.util.HashMap<>(fields);
        invalid.put("authority", "http://service.example");
        try { Invitation.fromSecrets(invalid); throw new AssertionError("HTTP accepted"); } catch (IOException expected) { }
        fails("hosthop1:" + Base64.getUrlEncoder().withoutPadding().encodeToString("{\"world\":\"a\",\"world\":\"b\"}".getBytes(StandardCharsets.UTF_8)));
        fails("hosthop1:not-valid!");
        System.out.println("Invitation checks passed");
    }
    private static void fails(String code) throws IOException {
        try { Invitation.decode(code); throw new AssertionError("invalid invitation accepted"); } catch (IOException expected) { }
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
