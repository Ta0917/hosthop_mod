package dev.hosthop.storage;

import java.io.IOException;

/** Supplies a currently valid Drive access token, refreshing it when needed. Call off the game thread. */
@FunctionalInterface
public interface DriveTokenSource {
    String accessToken() throws IOException, InterruptedException;
}
