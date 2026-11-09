package dev.hosthop.storage;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.function.Consumer;

/** Explicit consent and DPAPI-backed refresh credentials; call off the game thread. */
public final class DriveCredentialManager {
    public static final class ReauthorizationRequired extends IOException {
        private ReauthorizationRequired(Throwable cause) { super("Google Drive authorization expired or was revoked", cause); }
    }
    public static final class AccountMismatch extends IOException {
        private AccountMismatch() { super("Different Google account selected"); }
    }
    private static final String REFRESH = "drive_refresh_token";
    private static final String FOLDER = "drive_folder_id";
    private final WindowsSecretStore vault;
    private final GoogleDriveAuthorization authorization;
    private GoogleDriveAuthorization.Tokens cached;
    private long usableUntil;
    private final java.util.concurrent.atomic.AtomicBoolean authorizing = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicLong authorizationGeneration = new java.util.concurrent.atomic.AtomicLong();
    private volatile GoogleDriveAuthorization.Session activeSession;

    public DriveCredentialManager(WindowsSecretStore vault, GoogleDriveAuthorization authorization) {
        if (vault == null || authorization == null) throw new IllegalArgumentException("Missing credential dependency");
        this.vault = vault; this.authorization = authorization;
    }

    /** The caller supplies the browser opener as a result of an explicit user action. */
    public void authorize(String expectedFolder, Consumer<URI> openBrowser) throws IOException, InterruptedException {
        if (openBrowser == null) throw new IllegalArgumentException("Missing browser opener");
        if (!authorizing.compareAndSet(false, true)) throw new IOException("Drive authorization already in progress");
        long generation = authorizationGeneration.get();
        try (var session = authorization.begin(expectedFolder)) {
            activeSession = session;
            if (generation != authorizationGeneration.get()) throw new IOException("Drive authorization cancelled");
            openBrowser.accept(session.authorizationUri());
            var tokens = session.await(Duration.ofMinutes(5));
            if (generation != authorizationGeneration.get()) throw new IOException("Drive authorization cancelled");
            if (tokens.refreshToken() == null) throw new IOException("Google did not provide a refresh token");
            synchronized (this) { save(tokens); cache(tokens); }
        } finally {
            activeSession = null;
            authorizing.set(false);
        }
    }

    public void cancelAuthorization() {
        authorizationGeneration.incrementAndGet();
        var session = activeSession;
        if (session != null) session.close();
    }

    /** Explicitly grants access to a snapshot created by another member. */
    public void authorizeFile(String expectedFile, String expectedFolder, Consumer<URI> openBrowser) throws IOException, InterruptedException {
        if (openBrowser == null || expectedFile == null || !expectedFile.matches("[A-Za-z0-9_-]{1,200}")
                || expectedFolder == null || !expectedFolder.matches("[A-Za-z0-9_-]{1,200}"))
            throw new IllegalArgumentException("Invalid file authorization");
        if (!authorizing.compareAndSet(false, true)) throw new IOException("Drive authorization already in progress");
        long generation = authorizationGeneration.get();
        try {
            String originalAccount = authorization.accountId(access().accessToken());
            try (var session = authorization.begin(expectedFile)) {
            activeSession = session;
            if (generation != authorizationGeneration.get()) throw new IOException("Drive authorization cancelled");
            openBrowser.accept(session.authorizationUri());
            var selected = session.await(Duration.ofMinutes(5));
            if (generation != authorizationGeneration.get()) throw new IOException("Drive authorization cancelled");
            if (!originalAccount.equals(authorization.accountId(selected.accessToken()))) throw new AccountMismatch();
            var secrets = vault.load();
            if (!expectedFolder.equals(secrets.get(FOLDER))) throw new IOException("Drive folder does not match invitation");
            String refresh = selected.refreshToken() != null ? selected.refreshToken() : secrets.get(REFRESH);
            if (refresh == null) throw new IOException("Google did not provide a refresh token");
            var tokens = authorization.refresh(refresh, expectedFolder);
            if (!originalAccount.equals(authorization.accountId(tokens.accessToken()))) throw new AccountMismatch();
            synchronized (this) { save(tokens); cache(tokens); }
            }
        } finally {
            activeSession = null;
            authorizing.set(false);
        }
    }

    /** Refreshes credentials if needed; never opens a browser automatically. */
    public synchronized GoogleDriveAuthorization.Tokens access() throws IOException, InterruptedException {
        if (cached != null && System.nanoTime() - usableUntil < 0) return cached;
        if (!vault.exists()) throw new IOException("Drive authorization required");
        var secrets = vault.load();
        String refresh = secrets.get(REFRESH), folder = secrets.get(FOLDER);
        if (refresh == null || folder == null) throw new IOException("Drive authorization required");
        GoogleDriveAuthorization.Tokens tokens;
        try { tokens = authorization.refresh(refresh, folder); }
        catch (GoogleDriveAuthorization.InvalidGrant expired) { throw new ReauthorizationRequired(expired); }
        if (!refresh.equals(tokens.refreshToken())) save(tokens);
        cache(tokens);
        return tokens;
    }

    private void save(GoogleDriveAuthorization.Tokens tokens) throws IOException {
        var secrets = new HashMap<String, String>();
        if (vault.exists()) secrets.putAll(vault.load());
        secrets.put(REFRESH, tokens.refreshToken());
        secrets.put(FOLDER, tokens.folderId());
        vault.save(secrets);
    }

    private void cache(GoogleDriveAuthorization.Tokens tokens) {
        cached = tokens;
        long seconds = Math.max(0, tokens.expiresInSeconds() - 60);
        usableUntil = System.nanoTime() + seconds * 1_000_000_000L;
    }
}
