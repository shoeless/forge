package forge.gamemodes.net.server;

import forge.localinstance.properties.ForgeConstants;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Broadcasts the presence of a hosted game server via UDP on the LAN.
 * Sends a broadcast packet every 2 seconds on the discovery port so that
 * guests running {@link forge.gamemodes.net.client.ServerDiscovery} can
 * find available games without manually entering an IP address.
 *
 * Uses subnet-directed broadcast addresses (e.g. 192.168.1.255) computed from
 * LAN IPs found by {@link FServerManager#getAllLanAddresses()}. iOS blocks
 * sends to the global broadcast 255.255.255.255, and RoboVM's
 * InterfaceAddress.getBroadcast() doesn't reliably return Wi-Fi interfaces.
 */
public class ServerBroadcaster {
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
    private int currentPlayers;

    public ServerBroadcaster(String playerName, int gamePort, int maxPlayers) {
        this.playerName = playerName;
        this.gamePort = gamePort;
        this.maxPlayers = maxPlayers;
        this.currentPlayers = 1;
    }

    /**
     * Set the Tailscale API key for cloud-based peer discovery.
     */
    public void setTailscaleApiKey(String apiKey) {
        tailscaleResolver.setApiKey(apiKey);
    }

    public void setCurrentPlayers(int currentPlayers) {
        this.currentPlayers = currentPlayers;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        broadcastThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runBroadcastLoop();
            }
        }, "ServerBroadcaster");
        broadcastThread.setDaemon(true);
        broadcastThread.start();
    }

    public void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
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
     * This approach is more reliable on iOS than InterfaceAddress.getBroadcast()
     * which fails to return Wi-Fi interfaces under RoboVM.
     */
    private static List<InetAddress> getSubnetBroadcastAddresses() {
        List<InetAddress> result = new ArrayList<InetAddress>();
        List<String> lanAddresses = FServerManager.getAllLanAddresses();

        for (String addrStr : lanAddresses) {
            // Skip link-local addresses (169.254.x.x) — they don't work for broadcast
            if (addrStr.startsWith("169.254.")) {
                continue;
            }
            try {
                InetAddress addr = InetAddress.getByName(addrStr);
                byte[] ip = addr.getAddress();
                if (ip.length == 4) {
                    // Assume /24: set last octet to 255
                    byte[] broadcastBytes = new byte[] { ip[0], ip[1], ip[2], (byte) 255 };
                    InetAddress broadcast = InetAddress.getByAddress(broadcastBytes);
                    result.add(broadcast);
                    System.out.println("ServerBroadcaster: Will broadcast to " + broadcast.getHostAddress()
                            + " (from LAN IP " + addrStr + ")");
                }
            } catch (Exception e) {
                System.err.println("ServerBroadcaster: Error computing broadcast for " + addrStr + ": " + e.getMessage());
            }
        }

        if (result.isEmpty()) {
            System.err.println("ServerBroadcaster: No LAN addresses found, falling back to 255.255.255.255");
            try {
                result.add(InetAddress.getByName("255.255.255.255"));
            } catch (Exception e) {
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
                    String message = PROTOCOL_PREFIX + "|" + PROTOCOL_VERSION + "|"
                            + playerName + "|" + gamePort + "|"
                            + currentPlayers + "/" + maxPlayers;
                    byte[] data = message.getBytes("UTF-8");

                    // Send to all subnet broadcast addresses
                    List<InetAddress> broadcastAddresses = getSubnetBroadcastAddresses();
                    for (InetAddress addr : broadcastAddresses) {
                        try {
                            DatagramPacket packet = new DatagramPacket(
                                    data, data.length,
                                    addr, ForgeConstants.DISCOVERY_PORT);
                            socket.send(packet);
                        } catch (Exception e) {
                            if (running) {
                                System.err.println("ServerBroadcaster: Error sending to " + addr.getHostAddress() + ": " + e.getMessage());
                            }
                        }
                    }

                    // Send unicast to Tailscale peers (for cross-network discovery)
                    List<InetAddress> tailscalePeers = tailscaleResolver.getOnlinePeers();
                    for (InetAddress peerAddr : tailscalePeers) {
                        try {
                            DatagramPacket packet = new DatagramPacket(
                                    data, data.length,
                                    peerAddr, ForgeConstants.DISCOVERY_PORT);
                            socket.send(packet);
                        } catch (Exception e) {
                            if (running) {
                                System.err.println("ServerBroadcaster: Error sending to Tailscale peer "
                                        + peerAddr.getHostAddress() + ": " + e.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    if (running) {
                        System.err.println("ServerBroadcaster: Error preparing broadcast: " + e.getMessage());
                    }
                }

                try {
                    Thread.sleep(BROADCAST_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
            }
        } catch (Exception e) {
            if (running) {
                System.err.println("ServerBroadcaster: Failed to start: " + e.getMessage());
            }
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }
}
