package dev.hosthop.distribution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The publisher's Ed25519 key. Members trust only mod lists signed by the key they registered, so neither the
 * management service nor the shared Drive folder alone can make them run code. The public key is not secret;
 * the private key never leaves the publisher's PC.
 */
public final class PublisherKey {
    public static final String PREFIX = "hosthop-key1:";
    private static final int ENCODED_PUBLIC = 44;

    private PublisherKey() {}

    public static KeyPair generate() {
        try { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch (GeneralSecurityException impossible) { throw new IllegalStateException("Ed25519 unavailable", impossible); }
    }

    public static String encodePublic(PublicKey key) {
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(key.getEncoded());
    }

    public static PublicKey decodePublic(String code) throws IOException {
        if (code == null) throw new IOException("Missing publisher key");
        code = code.strip();
        if (!code.startsWith(PREFIX) || code.length() > 200) throw new IOException("Not a publisher key");
        try {
            byte[] encoded = Base64.getUrlDecoder().decode(code.substring(PREFIX.length()));
            if (encoded.length != ENCODED_PUBLIC) throw new IOException("Invalid publisher key");
            var key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
            if (!"EdDSA".equals(key.getAlgorithm()) && !"Ed25519".equals(key.getAlgorithm())) throw new IOException("Invalid publisher key");
            return key;
        } catch (IllegalArgumentException | GeneralSecurityException invalid) { throw new IOException("Invalid publisher key", invalid); }
    }

    public static String encodePrivate(PrivateKey key) { return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getEncoded()); }

    public static PrivateKey decodePrivate(String text) throws IOException {
        try { return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getUrlDecoder().decode(text))); }
        catch (RuntimeException | GeneralSecurityException invalid) { throw new IOException("Invalid publisher private key", invalid); }
    }

    public static String sign(PrivateKey key, String text) {
        try {
            var signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            signer.update(text.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("Signing failed", failure); }
    }

    public static boolean verify(PublicKey key, String text, String signature) {
        if (key == null || text == null || signature == null || !signature.matches("[A-Za-z0-9_-]{86}")) return false;
        try {
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(text.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getUrlDecoder().decode(signature));
        } catch (IllegalArgumentException | GeneralSecurityException invalid) { return false; }
    }

    /** A short code members can compare with the publisher by voice or chat, for example "3f2a-91c0-77de-4b10". */
    public static String fingerprint(PublicKey key) {
        try {
            String hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
            return hex.substring(0, 4) + "-" + hex.substring(4, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16);
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
}
