package forge.gamemodes.net.client;

import forge.localinstance.properties.ForgeConstants;
import forge.util.IHasForgeLog;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Listens for UDP broadcast packets from {@link forge.gamemodes.net.server.ServerBroadcaster}
 * to discover available game servers on the LAN without requiring manual IP entry.
 */
public class ServerDiscovery implements IHasForgeLog {
    private static final int RECEIVE_TIMEOUT_MS = 1000;
    private static final long STALE_THRESHOLD_MS = 6000;
    private static final String PROTOCOL_PREFIX = "FORGE_SERVER";
    private static final int BIND_RETRY_DELAY_MS = 500;

    private volatile boolean running;
    private Thread listenThread;
    private DatagramSocket socket;
    private final List<DiscoveredServer> servers = new ArrayList<>();
    private volatile ServerDiscoveryListener listener;

    public void setListener(final ServerDiscoveryListener listener) {
        this.listener = listener;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        listenThread = new Thread(this::runListenLoop, "ServerDiscovery");
        listenThread.setDaemon(true);
        listenThread.start();
    }

    /**
     * Stops the listen loop. Non-blocking — safe to call from the EDT.
     */
    public void stop() {
        running = false;
        final DatagramSocket s = socket;
        if (s != null && !s.isClosed()) {
            s.close();
        }
        if (listenThread != null) {
            listenThread.interrupt();
            listenThread = null;
        }
    }

    public List<DiscoveredServer> getServers() {
        synchronized (servers) {
            return new ArrayList<>(servers);
        }
    }

    private DatagramSocket bindSocket() throws Exception {
        try {
            return openAndBind();
        } catch (final Exception e) {
            // A previous scan may still hold the port for a moment — wait and retry once
            netLog.info("ServerDiscovery: bind failed ({}), retrying once", e.getMessage());
            Thread.sleep(BIND_RETRY_DELAY_MS);
            return openAndBind();
        }
    }

    private DatagramSocket openAndBind() throws Exception {
        final DatagramSocket s = new DatagramSocket(null); // unbound
        try {
            s.setReuseAddress(true);
            s.setBroadcast(true);
            s.bind(new InetSocketAddress(ForgeConstants.DISCOVERY_PORT));
            s.setSoTimeout(RECEIVE_TIMEOUT_MS);
            return s;
        } catch (final Exception e) {
            s.close();
            throw e;
        }
    }

    private void runListenLoop() {
        try {
            socket = bindSocket();
            netLog.info("ServerDiscovery: Listening on UDP port {}", ForgeConstants.DISCOVERY_PORT);

            final byte[] buffer = new byte[1024];

            while (running) {
                try {
                    final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    final String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    final String sourceAddress = packet.getAddress().getHostAddress();
                    netLog.debug("ServerDiscovery: Received packet from {}: {}", sourceAddress, message);
                    processPacket(sourceAddress, message);
                } catch (final SocketTimeoutException e) {
                    // Expected — allows us to check running flag and prune stale servers
                }

                pruneStaleServers();
            }
        } catch (final Throwable t) {
            if (running) {
                netLog.error("ServerDiscovery: Failed to start: {}", t.getMessage());
            }
        } finally {
            final DatagramSocket s = socket;
            if (s != null && !s.isClosed()) {
                s.close();
            }
        }
    }

    private void processPacket(final String sourceAddress, final String message) {
        // Format: FORGE_SERVER|<version>|<hostName>|<gamePort>|<current>/<max>[|<hostIPs>]
        final String[] parts = message.split("\\|");
        if (parts.length < 5 || !PROTOCOL_PREFIX.equals(parts[0])) {
            return;
        }

        try {
            final int version = Integer.parseInt(parts[1]);
            if (version != 1) {
                return;
            }

            final String hostName = parts[2];
            final int gamePort = Integer.parseInt(parts[3]);
            final String playerCount = parts[4];

            // Use UDP source IP as primary (it delivered this packet, so it's routable).
            // Keep all host-reported IPs for fallback in case UDP source is unreachable.
            String fallback = null;
            final List<String> allHostIps = new ArrayList<>();
            if (parts.length >= 6 && !parts[5].isEmpty()) {
                for (final String ip : parts[5].split(",")) {
                    final String trimmed = ip.trim();
                    if (!trimmed.isEmpty()) {
                        allHostIps.add(trimmed);
                    }
                }
                if (!allHostIps.isEmpty()) {
                    final String hostReported = allHostIps.get(0);
                    if (!hostReported.equals(sourceAddress)) {
                        fallback = hostReported;
                    }
                }
                netLog.debug("ServerDiscovery: UDP source={} host-reported={} IPs", sourceAddress, allHostIps.size());
            }

            final DiscoveredServer entry = new DiscoveredServer(
                    sourceAddress, fallback, allHostIps, hostName, gamePort, playerCount);
            synchronized (servers) {
                boolean updated = false;
                // Update existing server entry or add new one
                for (int i = 0; i < servers.size(); i++) {
                    final DiscoveredServer existing = servers.get(i);
                    if (existing.address().equals(sourceAddress) && existing.gamePort() == gamePort) {
                        servers.set(i, entry);
                        updated = true;
                        break;
                    }
                }
                if (!updated) {
                    servers.add(entry);
                }
            }

            notifyListener();
        } catch (final NumberFormatException e) {
            // Malformed packet, ignore
        }
    }

    private void pruneStaleServers() {
        final long now = System.currentTimeMillis();
        final boolean pruned;
        synchronized (servers) {
            pruned = servers.removeIf(server -> now - server.lastSeen() > STALE_THRESHOLD_MS);
        }
        if (pruned) {
            notifyListener();
        }
    }

    private void notifyListener() {
        final ServerDiscoveryListener l = listener;
        if (l != null) {
            l.onServerListUpdated(getServers());
        }
    }

    /**
     * Represents a game server discovered via LAN broadcast.
     *
     * @param address         the UDP source address that delivered the broadcast (primary)
     * @param fallbackAddress the first host-reported IP if it differs from the source
     * @param allHostIps      all host-reported IPs (for multi-address fallback)
     */
    public record DiscoveredServer(String address, String fallbackAddress, List<String> allHostIps,
                                   String hostName, int gamePort, String playerCount, long lastSeen) {
        public DiscoveredServer {
            allHostIps = allHostIps == null ? List.of() : List.copyOf(allHostIps);
        }

        DiscoveredServer(final String address, final String fallbackAddress, final List<String> allHostIps,
                         final String hostName, final int gamePort, final String playerCount) {
            this(address, fallbackAddress, allHostIps, hostName, gamePort, playerCount, System.currentTimeMillis());
        }

        @Override
        public String toString() {
            final String addrDisplay = address.contains(":")
                    ? "[" + address + "]:" + gamePort
                    : address + ":" + gamePort;
            return hostName + " (" + addrDisplay + ") [" + playerCount + "]";
        }
    }

    /**
     * Callback interface for server list changes. Invoked on the discovery
     * thread — implementations must hop to the EDT before touching the UI.
     */
    @FunctionalInterface
    public interface ServerDiscoveryListener {
        void onServerListUpdated(List<DiscoveredServer> servers);
    }
}
