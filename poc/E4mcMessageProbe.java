import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Offline research spike: inspect one known e4mc JAR, never connects to a relay. */
public final class E4mcMessageProbe {
    private static final String EXPECTED_SHA256 =
            "6000692f216faa67f8a6be26fad6dffd54f1c229ee290f355cd42792c4cdbd57";
    private static final String MESSAGE = "link.e4mc.QuiclimeSession$ControlMessageCodec$DomainAssignmentCompleteMessageClientbound";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass the e4mc JAR path");
        Path jar = Path.of(args[0]);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(jar)) {
            byte[] bytes = new byte[65536];
            for (int count; (count = input.read(bytes)) != -1;) digest.update(bytes, 0, count);
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(EXPECTED_SHA256)) {
            throw new IllegalArgumentException("Unverified e4mc JAR; refusing to probe");
        }
        try (var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, null)) {
            Class<?> type = Class.forName(MESSAGE, true, loader);
            String testDomain = "sample.invalid";
            Object message = type.getConstructor(String.class).newInstance(testDomain);
            var field = type.getDeclaredField("domain");
            if (!field.trySetAccessible() || !testDomain.equals(field.get(message))) {
                throw new IllegalStateException("Domain field could not be read");
            }
            System.out.println("PASS: known e4mc assignment message field is readable");
        }
    }
}
