package dev.hosthop.lease;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import javax.net.ssl.SSLContext;

/** Isolated test TLS trust; never included in normal builds. */
public final class TestAuthority {
    private TestAuthority() {}
    public static LeaseAuthorityClient create(URI base, String token, SSLContext tls) {
        var http = HttpClient.newBuilder().sslContext(tls).followRedirects(HttpClient.Redirect.NEVER).build();
        return new LeaseAuthorityClient(base, token, LeaseAuthorityClient.httpExchange(http, Duration.ofSeconds(5)), System::nanoTime, System::currentTimeMillis);
    }
}
