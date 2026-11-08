package dev.hosthop.probe;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Opt-in experiment: creates an empty, temporary public e4mc tunnel. */
@EventBusSubscriber(modid = "hosthop")
public final class E4mcRelayProbe {
    public static volatile boolean finished;
    public static volatile Throwable failure;
    private static int localPort;

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) throws Exception {
        MinecraftServer server = event.getServer();
        if (!Boolean.getBoolean("hosthop.e4mcRelayProbe") ||
                !server.getClass().getName().equals("net.minecraft.gametest.framework.GameTestServer")) return;
        System.out.println("HOSTHOP_PROBE starting loopback listener for empty test world");
        boolean localOnly = Boolean.getBoolean("hosthop.e4mcLocalOnly");
        if (localOnly) {
            try (ServerSocket reserved = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                localPort = reserved.getLocalPort();
            }
        }
        server.getConnection().startTcpServerListener(InetAddress.getByName("127.0.0.1"), localPort);
        Thread probe = new Thread(() -> runProbe(server), "hosthop-relay-probe");
        probe.setDaemon(true);
        probe.start();
    }

    private static void runProbe(MinecraftServer server) {
        Object session = null;
        try {
            if (Boolean.getBoolean("hosthop.e4mcLocalOnly")) {
                waitForStatus(server);
                ping("127.0.0.1", localPort, SharedConstants.getCurrentVersion().protocolVersion());
                System.out.println("HOSTHOP_PROBE local Minecraft status response verified=true");
                return;
            }
            Class<?> client = Class.forName("link.e4mc.E4mcClient");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            String state = "NO_SESSION";
            while (System.nanoTime() < deadline) {
                session = client.getField("session").get(null);
                if (session != null) {
                    state = String.valueOf(session.getClass().getField("state").get(session));
                    if (state.equals("STARTED") || state.equals("UNHEALTHY") || state.equals("STOPPED")) break;
                }
                Thread.sleep(100);
            }
            System.out.println("HOSTHOP_PROBE final session state=" + state);
            System.out.println("HOSTHOP_PROBE assignment captured=" + (CapturedEndpoint.address() != null));
            if (!state.equals("STARTED") || CapturedEndpoint.address() == null) throw new IllegalStateException("Relay or assignment capture failed");
            waitForStatus(server);
            int protocol = SharedConstants.getCurrentVersion().protocolVersion();
            ping(CapturedEndpoint.address(), 25565, protocol);
            System.out.println("HOSTHOP_PROBE Minecraft status response verified=true");
            int holdSeconds = Math.max(0, Math.min(60, Integer.getInteger("hosthop.e4mcHoldSeconds", 0)));
            if (holdSeconds > 0) {
                System.out.println("HOSTHOP_PROBE monitoring interval started");
                Thread.sleep(TimeUnit.SECONDS.toMillis(holdSeconds));
                System.out.println("HOSTHOP_PROBE monitoring interval ended");
            }
            if (Boolean.getBoolean("hosthop.e4mcDropProbe")) {
                String guestHost = CapturedEndpoint.address();
                splitPing(guestHost, 25565, protocol);
                System.out.println("HOSTHOP_PROBE split handshake control verified=true");
                byte[] handshake = statusHandshake(guestHost, 25565, protocol);
                try (Socket guest = new Socket()) {
                    guest.connect(new InetSocketAddress(guestHost, 25565), 5000);
                    guest.setSoTimeout(500);
                    guest.getOutputStream().write(handshake, 0, 1); // Send only the first byte of the frame.
                    guest.getOutputStream().flush();
                    try {
                        guest.getInputStream().read();
                        throw new IOException("Guest connection closed before the relay test");
                    } catch (SocketTimeoutException expected) {
                        System.out.println("HOSTHOP_PROBE guest stream initially open=true");
                    }
                    Object laterSession = null;
                    if (Boolean.getBoolean("hosthop.e4mcStaleProbe")) {
                        // Construct an unstarted e4mc session. It opens no connection,
                        // but exercises the actual constructor and stop mixins.
                        laterSession = session.getClass().getConstructor(io.netty.channel.ChannelHandler.class,
                                io.netty.channel.EventLoopGroup.class).newInstance(null, null);
                        if (!CapturedEndpoint.publish(laterSession, "test.invalid") ||
                                CapturedEndpoint.publish(session, "stale.invalid")) {
                            throw new IllegalStateException("Stale assignment was not fenced");
                        }
                        System.out.println("HOSTHOP_PROBE old assignment rejected after replacement=true");
                    }
                    var channelField = session.getClass().getDeclaredField("quicChannel");
                    if (!channelField.trySetAccessible()) throw new IllegalStateException("Cannot inspect relay channel");
                    Object channel = channelField.get(session);
                    if (!(channel instanceof io.netty.channel.Channel relay)) throw new IllegalStateException("Relay channel unavailable");
                    relay.close().syncUninterruptibly();
                    if (laterSession == null) {
                        long dropDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                        while (CapturedEndpoint.address() != null && System.nanoTime() < dropDeadline) Thread.sleep(50);
                        boolean cleared = CapturedEndpoint.address() == null;
                        System.out.println("HOSTHOP_PROBE assignment absent after forced channel close=" + cleared);
                        if (!cleared) throw new IllegalStateException("Stale assignment after channel close");
                    } else {
                        // Wait for the old channel's inactive callback before checking.
                        long inactiveDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                        while (!String.valueOf(session.getClass().getField("state").get(session)).equals("STOPPED") &&
                                System.nanoTime() < inactiveDeadline) Thread.sleep(50);
                        if (!String.valueOf(session.getClass().getField("state").get(session)).equals("STOPPED")) {
                            throw new IllegalStateException("Old channel did not become inactive");
                        }
                        boolean protectedNew = "test.invalid".equals(CapturedEndpoint.address());
                        System.out.println("HOSTHOP_PROBE old channel close preserved new assignment=" + protectedNew);
                        if (!protectedNew) throw new IllegalStateException("Old channel invalidated new session");
                        laterSession.getClass().getMethod("stop").invoke(laterSession);
                        if (CapturedEndpoint.address() != null) throw new IllegalStateException("New session stop left assignment");
                    }
                    boolean statusAfterClose;
                    guest.setSoTimeout(8000);
                    try {
                        guest.getOutputStream().write(handshake, 1, handshake.length - 1);
                        guest.getOutputStream().write(new byte[]{1, 0});
                        guest.getOutputStream().flush();
                        readStatus(guest, protocol);
                        statusAfterClose = true;
                    } catch (IOException failed) {
                        statusAfterClose = false;
                    }
                    System.out.println("HOSTHOP_PROBE guest status response after relay close=" + statusAfterClose);
                }
            }
        } catch (Throwable e) {
            failure = e;
            System.out.println("HOSTHOP_PROBE failure=" + e.getClass().getSimpleName());
        } finally {
            if (session != null) {
                try {
                    session.getClass().getMethod("stop").invoke(session);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    String state;
                    do {
                        state = String.valueOf(session.getClass().getField("state").get(session));
                        if (state.equals("STOPPED")) break;
                        Thread.sleep(100);
                    } while (System.nanoTime() < deadline);
                    boolean stopped = state.equals("STOPPED") && CapturedEndpoint.address() == null;
                    System.out.println("HOSTHOP_PROBE stopped and assignment absent=" + stopped);
                    if (!stopped && failure == null) failure = new IllegalStateException("Session did not stop cleanly");
                } catch (Throwable e) {
                    if (failure == null) failure = e;
                }
            }
            finished = true;
        }
    }

    private static void waitForStatus(MinecraftServer server) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (server.getStatus() == null && System.nanoTime() < deadline) Thread.sleep(50);
        if (server.getStatus() == null) throw new IllegalStateException("Server status was not initialized");
    }

    private static void ping(String host, int port, int protocol) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(8000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write(statusHandshake(host, port, protocol));
            out.writeByte(1);
            out.writeByte(0);
            out.flush();
            readStatus(socket, protocol);
        }
    }

    private static byte[] statusHandshake(String host, int port, int protocol) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream handshake = new DataOutputStream(body);
        writeVarInt(handshake, 0);
        writeVarInt(handshake, protocol);
        byte[] address = host.getBytes(StandardCharsets.UTF_8);
        writeVarInt(handshake, address.length);
        handshake.write(address);
        handshake.writeShort(port);
        writeVarInt(handshake, 1);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        writeVarInt(new DataOutputStream(frame), body.size());
        body.writeTo(frame);
        return frame.toByteArray();
    }

    private static void splitPing(String host, int port, int protocol) throws IOException {
        byte[] handshake = statusHandshake(host, port, protocol);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(500);
            socket.getOutputStream().write(handshake, 0, 1);
            socket.getOutputStream().flush();
            try {
                socket.getInputStream().read();
                throw new IOException("Guest connection closed before split handshake");
            } catch (SocketTimeoutException expected) {
                // A quiet but open stream is expected while the frame is incomplete.
            }
            socket.setSoTimeout(8000);
            socket.getOutputStream().write(handshake, 1, handshake.length - 1);
            socket.getOutputStream().write(new byte[]{1, 0});
            socket.getOutputStream().flush();
            readStatus(socket, protocol);
        }
    }

    private static void readStatus(Socket socket, int protocol) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        int length = readVarInt(in);
        if (length <= 0 || length > 1_000_000) throw new IOException("Invalid response length");
        byte[] frame = in.readNBytes(length);
        if (frame.length != length) throw new IOException("Incomplete response");
        DataInputStream response = new DataInputStream(new ByteArrayInputStream(frame));
        if (readVarInt(response) != 0) throw new IOException("Unexpected status packet");
        int jsonLength = readVarInt(response);
        if (jsonLength < 0 || jsonLength != response.available()) throw new IOException("Invalid status JSON length");
        String json = new String(response.readNBytes(jsonLength), StandardCharsets.UTF_8);
        var version = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("version");
        if (version == null || version.get("protocol").getAsInt() != protocol) throw new IOException("Unexpected protocol version");
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        do { int b = value & 127; value >>>= 7; out.writeByte(value == 0 ? b : b | 128); } while (value != 0);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte();
            value |= (b & 127) << shift;
            if ((b & 128) == 0) return value;
        }
        throw new IOException("Invalid VarInt");
    }
}
