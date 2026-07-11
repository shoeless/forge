package forge.gamemodes.net.server;

import forge.localinstance.properties.ForgeConstants;
import forge.util.IHasForgeLog;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Broadcasts the presence of a hosted game server via UDP on the LAN.
 * Sends a broadcast packet every 2 seconds on the discovery port so that
 * guests running {@link forge.gamemodes.net.client.ServerDiscovery} can
 * find available games without manually entering an IP address.
 *
 * <p>Uses subnet-directed broadcast addresses (e.g. 192.168.1.255) computed from
 * LAN IPs found by {@link FServerManager#getAllLanAddresses()}. iOS blocks
 * sends to the global broadcast 255.255.255.255, and RoboVM's
 * InterfaceAddress.getBroadcast() doesn't reliably return Wi-Fi interfaces.</p>
 */
public class ServerBroadcaster implements IHasForgeLog {
    private static final int BROADCAST_INTERVAL_MS = 2000;
    private static final String PROTOCOL_PREFIX = "FORGE_SERVER";
    private static final int PROTOCOL_VERSION = 1;

    private volatile boolean running;
    private Thread broadcastThread;
    private DatagramSocket socket;
    private final TailscalePeerResolver tailscaleResolver = new TailscalePeerResolver();

    private final String playerName;
    private final int gamePort;
    private final int maxPlayers;
    private volatile int currentPlayers;

    public ServerBroadcaster(final String playerName, final int gamePort, final int maxPlayers) {
        this.playerName = playerName;
        this.gamePort = gamePort;
        this.maxPlayers = maxPlayers;
        this.currentPlayers = 1;
    }

    /**
     * Configure the Tailscale credential (OAuth client id + secret, or a legacy API token as
     * the secret) used to enumerate tailnet peers for cross-network discovery unicast.
     */
    public void setTailscaleCredentials(final String clientId, final String clientSecret) {
        tailscaleResolver.setCredentials(clientId, clientSecret);
    }

    public void setCurrentPlayers(final int currentPlayers) {
        this.currentPlayers = currentPlayers;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        broadcastThread = new Thread(this::runBroadcastLoop, "ServerBroadcaster");
        broadcastThread.setDaemon(true);
        broadcastThread.start();
    }

    public void stop() {
        running = false;
        final DatagramSocket s = socket;
        if (s != null && !s.isClosed()) {
            s.close();
        }
        if (broadcastThread != null) {
            broadcastThread.interrupt();
            broadcastThread = null;
        }
    }

    /**
     * Computes subnet-directed broadcast addresses from LAN IPs found by
     * FServerManager.getAllLanAddresses(). Assumes /24 subnet (most home/office
     * networks). E.g. 192.168.1.42 → 192.168.1.255.
     *
     * <p>This approach is more reliable on iOS than InterfaceAddress.getBroadcast()
     * which fails to return Wi-Fi interfaces under RoboVM.</p>
     */
    private static List<InetAddress> getSubnetBroadcastAddresses() {
        final List<InetAddress> result = new ArrayList<>();
        for (final String addrStr : FServerManager.getAllLanAddresses()) {
            // Skip link-local addresses (169.254.x.x) — they don't work for broadcast
            if (addrStr.startsWith("169.254.")) {
                continue;
            }
            try {
                final byte[] ip = InetAddress.getByName(addrStr).getAddress();
                if (ip.length == 4) {
                    // Assume /24: set last octet to 255
                    final InetAddress broadcast = InetAddress.getByAddress(
                            new byte[] { ip[0], ip[1], ip[2], (byte) 255 });
                    result.add(broadcast);
                    netLog.debug("ServerBroadcaster: Will broadcast to {} (from LAN IP {})",
                            broadcast.getHostAddress(), addrStr);
                }
            } catch (final Exception e) {
                netLog.error("ServerBroadcaster: Error computing broadcast for {}: {}", addrStr, e.getMessage());
            }
        }

        if (result.isEmpty()) {
            netLog.warn("ServerBroadcaster: No LAN addresses found, falling back to 255.255.255.255");
            try {
                result.add(InetAddress.getByName("255.255.255.255"));
            } catch (final Exception e) {
                // Should never happen
            }
        }
        return result;
    }

    private void runBroadcastLoop() {
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);

            while (running) {
                try {
                    // Build host IP list for cross-network discovery.
                    // Includes IPv6 addresses (listed first) for better Tailscale
                    // direct connections. IPv6 bypasses CGNAT.
                    final String hostIps = String.join(",", FServerManager.getAllAddressesForDiscovery());
                    final String message = PROTOCOL_PREFIX + "|" + PROTOCOL_VERSION + "|"
                            + playerName + "|" + gamePort + "|"
                            + currentPlayers + "/" + maxPlayers + "|"
                            + hostIps;
                    final byte[] data = message.getBytes(StandardCharsets.UTF_8);

                    // Send to all subnet broadcast addresses (same-LAN discovery)
                    for (final InetAddress addr : getSubnetBroadcastAddresses()) {
                        send(data, addr, "broadcast address");
                    }

                    // Unicast to tailnet peers so cross-network guests auto-discover this host.
                    // getOnlinePeers never blocks (returns its cache; refreshes on a background
                    // thread) so this can't stall the LAN broadcast cadence — guests prune a host
                    // after 6s of silence. Empty unless a Tailscale interface is present.
                    for (final InetAddress peerAddr : tailscaleResolver.getOnlinePeers()) {
                        send(data, peerAddr, "Tailscale peer");
                    }
                } catch (final Exception e) {
                    if (running) {
                        netLog.error("ServerBroadcaster: Error preparing broadcast: {}", e.getMessage());
                    }
                }

                try {
                    Thread.sleep(BROADCAST_INTERVAL_MS);
                } catch (final InterruptedException e) {
                    break;
                }
            }
        } catch (final Throwable t) {
            if (running) {
                netLog.error("ServerBroadcaster: Failed to start: {}", t.getMessage());
            }
        } finally {
            final DatagramSocket s = socket;
            if (s != null && !s.isClosed()) {
                s.close();
            }
        }
    }

    private void send(final byte[] data, final InetAddress addr, final String kind) {
        try {
            socket.send(new DatagramPacket(data, data.length, addr, ForgeConstants.DISCOVERY_PORT));
        } catch (final Exception e) {
            if (running) {
                netLog.error("ServerBroadcaster: Error sending to {} {}: {}",
                        kind, addr.getHostAddress(), e.getMessage());
            }
        }
    }
}
