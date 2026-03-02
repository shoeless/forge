package forge.gamemodes.net.client;

import forge.localinstance.properties.ForgeConstants;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Listens for UDP broadcast packets from {@link forge.gamemodes.net.server.ServerBroadcaster}
 * to discover available game servers on the LAN without requiring manual IP entry.
 */
public class ServerDiscovery {
    private static final int RECEIVE_TIMEOUT_MS = 1000;
    private static final long STALE_THRESHOLD_MS = 6000;
    private static final String PROTOCOL_PREFIX = "FORGE_SERVER";

    private volatile boolean running;
    private Thread listenThread;
    private DatagramSocket socket;
    private final List<DiscoveredServer> servers = new ArrayList<DiscoveredServer>();
    private ServerDiscoveryListener listener;

    public void setListener(ServerDiscoveryListener listener) {
        this.listener = listener;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        listenThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runListenLoop();
            }
        }, "ServerDiscovery");
        listenThread.setDaemon(true);
        listenThread.start();
    }

    public void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (listenThread != null) {
            listenThread.interrupt();
            listenThread = null;
        }
        // Give the OS time to release the port before the next scan rebinds
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public List<DiscoveredServer> getServers() {
        synchronized (servers) {
            return new ArrayList<DiscoveredServer>(servers);
        }
    }

    private void runListenLoop() {
        try {
            socket = new DatagramSocket(null); // unbound
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(ForgeConstants.DISCOVERY_PORT));
            socket.setSoTimeout(RECEIVE_TIMEOUT_MS);
            System.out.println("ServerDiscovery: Listening on UDP port " + ForgeConstants.DISCOVERY_PORT);

            byte[] buffer = new byte[1024];

            while (running) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    String message = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                    String sourceAddress = packet.getAddress().getHostAddress();
                    System.out.println("ServerDiscovery: Received packet from " + sourceAddress + ": " + message);
                    processPacket(sourceAddress, message);
                } catch (SocketTimeoutException e) {
                    // Expected — allows us to check running flag and prune stale servers
                }

                pruneStaleServers();
            }
        } catch (Exception e) {
            if (running) {
                System.err.println("ServerDiscovery: Failed to start: " + e.getMessage());
            }
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    private void processPacket(String sourceAddress, String message) {
        // Format: FORGE_SERVER|<version>|<hostName>|<gamePort>|<current>/<max>
        String[] parts = message.split("\\|");
        if (parts.length < 5 || !PROTOCOL_PREFIX.equals(parts[0])) {
            return;
        }

        try {
            int version = Integer.parseInt(parts[1]);
            if (version != 1) {
                return;
            }

            String hostName = parts[2];
            int gamePort = Integer.parseInt(parts[3]);
            String playerCount = parts[4];

            boolean updated = false;
            synchronized (servers) {
                // Update existing server entry or add new one
                for (int i = 0; i < servers.size(); i++) {
                    DiscoveredServer existing = servers.get(i);
                    if (existing.address.equals(sourceAddress) && existing.gamePort == gamePort) {
                        servers.set(i, new DiscoveredServer(sourceAddress, hostName, gamePort, playerCount));
                        updated = true;
                        break;
                    }
                }
                if (!updated) {
                    servers.add(new DiscoveredServer(sourceAddress, hostName, gamePort, playerCount));
                }
            }

            if (listener != null) {
                listener.onServerListUpdated(getServers());
            }
        } catch (NumberFormatException e) {
            // Malformed packet, ignore
        }
    }

    private void pruneStaleServers() {
        long now = System.currentTimeMillis();
        boolean pruned = false;
        synchronized (servers) {
            Iterator<DiscoveredServer> it = servers.iterator();
            while (it.hasNext()) {
                DiscoveredServer server = it.next();
                if (now - server.lastSeen > STALE_THRESHOLD_MS) {
                    it.remove();
                    pruned = true;
                }
            }
        }
        if (pruned && listener != null) {
            listener.onServerListUpdated(getServers());
        }
    }

    /**
     * Represents a game server discovered via LAN broadcast.
     */
    public static final class DiscoveredServer {
        public final String address;
        public final String hostName;
        public final int gamePort;
        public final String playerCount;
        public final long lastSeen;

        DiscoveredServer(String address, String hostName, int gamePort, String playerCount) {
            this.address = address;
            this.hostName = hostName;
            this.gamePort = gamePort;
            this.playerCount = playerCount;
            this.lastSeen = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return hostName + " (" + address + ":" + gamePort + ") [" + playerCount + "]";
        }
    }

    /**
     * Callback interface for server list changes.
     */
    public interface ServerDiscoveryListener {
        void onServerListUpdated(List<DiscoveredServer> servers);
    }
}
