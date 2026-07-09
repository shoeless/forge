package forge.gamemodes.net;

import forge.gamemodes.match.AbstractGuiGame;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.client.ServerDiscovery;
import forge.gamemodes.net.event.IdentifiableNetEvent;
import forge.gamemodes.net.event.MessageEvent;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.localinstance.properties.ForgeNetPreferences;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.gui.interfaces.ILobbyView;
import forge.gui.util.SOptionPane;
import forge.interfaces.ILobbyListener;
import forge.interfaces.IUpdateable;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.Localizer;
import forge.util.URLValidator;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class NetConnectUtil {
    private NetConnectUtil() { }

    /**
     * Prompt for the server address to join. Returns null if cancelled, or the address string.
     */
    public static String getJoinServerUrl() {
        final String url = SOptionPane.showInputDialog(
                Localizer.getInstance().getMessage("lblEnterServerAddress"),
                Localizer.getInstance().getMessage("lblJoinGame"));
        if (url == null || url.isEmpty()) { return null; }

        ensurePlayerName();
        return url;
    }

    /**
     * Ensure the player name is set before connecting.
     */
    public static void ensurePlayerName() {
        if (StringUtils.isBlank(FModel.getPreferences().getPref(FPref.PLAYER_NAME))) {
            GamePlayerUtil.setPlayerName();
        }
    }

    public static ChatMessage host(final IOnlineLobby onlineLobby, final IOnlineChatInterface chatInterface) {
        final int port = FModel.getNetPreferences().getPrefInt(ForgeNetPreferences.FNetPref.NET_PORT);
        final FServerManager server = FServerManager.getInstance();
        final ServerGameLobby lobby = new ServerGameLobby();
        final ILobbyView view = onlineLobby.setLobby(lobby);

        NetworkLogConfig.activateNetworkLogging();
        server.startServer(port);
        server.setLobby(lobby);

        lobby.setListener(new IUpdateable() {
            @Override
            public void update(final boolean fullUpdate) {
                view.update(fullUpdate);
                server.updateLobbyState();
            }
            @Override
            public void update(final int slot, final LobbySlotType type) {}
        });
        // updateSlot already routes through the IUpdateable listener above, which calls
        // updateLobbyState; calling it again here would broadcast a duplicate LobbyUpdateEvent.
        view.setPlayerChangeListener(server::updateSlot);

        server.setLobbyListener(new ILobbyListener() {
            @Override
            public void update(final GameLobbyData state, final int slot) {
                // NO-OP, lobby connected directly
            }
            @Override
            public void message(final String source, final String message, final ChatMessage.MessageType type) {
                chatInterface.addMessage(new ChatMessage(source, message, type));
            }
            @Override
            public void close() {
                // NO-OP, server can't receive close message
            }
            @Override
            public ClientGameLobby getLobby() {
                return null;
            }
        });
        server.setDraftHandler(view.getDraftHandler());
        chatInterface.setGameClient(new IRemote() {
            @Override
            public void send(final NetEvent event) {
                if (event instanceof MessageEvent message) {
                    if (server.handleCommand(message.getMessage())) {
                        return;
                    }
                    server.broadcast(event);
                }
            }
            @Override
            public Object sendAndWait(final IdentifiableNetEvent event) {
                send(event);
                return null;
            }
        });

        view.update(true);

        server.broadcast(new MessageEvent(server.formatAfkTimeoutMessage()));

        return new ChatMessage(null, Localizer.getInstance().getMessage("lblHostingPortOnN", String.valueOf(port)));
    }

    public static void copyHostedServerUrl() {
        final Localizer localizer = Localizer.getInstance();
        String internalAddress = FServerManager.getLocalAddress();
        String externalAddress = FServerManager.getExternalAddress();
        String internalUrl = internalAddress + ":" + FModel.getNetPreferences().getPrefInt(ForgeNetPreferences.FNetPref.NET_PORT);
        String externalUrl = null;
        if (externalAddress != null) {
            externalUrl = externalAddress + ":" + FModel.getNetPreferences().getPrefInt(ForgeNetPreferences.FNetPref.NET_PORT);
            GuiBase.getInterface().copyToClipboard(externalUrl);
        } else {
            GuiBase.getInterface().copyToClipboard(internalUrl);
        }

        String message;
        String title = localizer.getMessage("lblServerURL");
        List<String> options;
        int closeIndex;
        int localCopyIndex;

        if (externalUrl != null) {
            message = localizer.getMessage("lblShareURLToMakePlayerJoinServer", externalUrl, internalUrl);
            options = List.of(
                    localizer.getMessage("lblCopyExternalURL"),
                    localizer.getMessage("lblCopyLocalURL"),
                    localizer.getMessage("lblClose"));
            closeIndex = 2;
            localCopyIndex = 1;
        } else {
            message = localizer.getMessage("lblForgeUnableDetermineYourExternalIP", internalUrl);
            options = List.of(
                    localizer.getMessage("lblCopyLocalURL"),
                    localizer.getMessage("lblClose"));
            closeIndex = 1;
            localCopyIndex = 0;
        }

        int result = SOptionPane.showOptionDialog(message, title, SOptionPane.INFORMATION_ICON, options, closeIndex);
        if (externalUrl != null && result == 0) {
            GuiBase.getInterface().copyToClipboard(externalUrl);
        } else if (result == localCopyIndex) {
            GuiBase.getInterface().copyToClipboard(internalUrl);
        }
    }

    /**
     * Builds the ordered list of candidate URLs to try for a discovered server.
     * Addresses are prioritized for cross-network reliability:
     * <ol>
     *   <li>Tailscale CGNAT addresses (100.64.0.0/10) — most likely to work cross-network</li>
     *   <li>UDP source IP / private LAN IPs (192.168.x, 10.x, 172.16-31.x)</li>
     *   <li>Everything else (public IPv6, etc.)</li>
     * </ol>
     * Within each bucket, the UDP source address (which delivered the discovery
     * packet, so it's known routable) keeps its position ahead of host-reported IPs.
     */
    public static List<String> getPrioritizedServerUrls(final ServerDiscovery.DiscoveredServer server) {
        final List<String> allIps = new ArrayList<>();
        allIps.add(server.address());
        for (final String ip : server.allHostIps()) {
            if (!allIps.contains(ip)) {
                allIps.add(ip);
            }
        }
        final List<String> ordered = new ArrayList<>(allIps.size());
        allIps.stream().filter(NetConnectUtil::isTailscaleAddress).forEach(ordered::add);
        allIps.stream().filter(ip -> !isTailscaleAddress(ip) && isPrivateAddress(ip)).forEach(ordered::add);
        allIps.stream().filter(ip -> !isTailscaleAddress(ip) && !isPrivateAddress(ip)).forEach(ordered::add);
        return ordered.stream().map(ip -> formatAddressPort(ip, server.gamePort())).toList();
    }

    /**
     * Formats an address and port for URL parsing. Brackets IPv6 addresses
     * so the URI parser can distinguish address from port.
     */
    private static String formatAddressPort(final String address, final int port) {
        if (address.contains(":")) {
            return "[" + address + "]:" + port;
        }
        return address + ":" + port;
    }

    /** Returns true if the IP is in Tailscale's CGNAT range (100.64.0.0/10). */
    private static boolean isTailscaleAddress(final String ip) {
        final int[] octets = parseIPv4(ip);
        // 100.64.0.0/10 = 100.64.0.0 - 100.127.255.255
        return octets != null && octets[0] == 100 && octets[1] >= 64 && octets[1] <= 127;
    }

    /** Returns true if the IP is a private/LAN address (192.168.x, 10.x, 172.16-31.x). */
    private static boolean isPrivateAddress(final String ip) {
        final int[] octets = parseIPv4(ip);
        if (octets == null) {
            return false;
        }
        return octets[0] == 10
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168);
    }

    /** Parses a dotted-quad IPv4 string; returns null for IPv6 or malformed input. */
    private static int[] parseIPv4(final String ip) {
        if (ip == null || ip.contains(":")) {
            return null; // IPv6
        }
        final String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        try {
            final int[] octets = new int[4];
            for (int i = 0; i < 4; i++) {
                octets[i] = Integer.parseInt(parts[i]);
            }
            return octets;
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    public static ChatMessage join(final String url, final IOnlineLobby onlineLobby, final IOnlineChatInterface chatInterface) {
        final IGuiGame gui = GuiBase.getInterface().getNewGuiGame();
        String hostname;
        int port;

        URLValidator.HostPort hostPort = URLValidator.parseURL(url);
        if (hostPort == null) {
            return new ChatMessage(null, ForgeConstants.INVALID_HOST_COMMAND);
        }

        hostname = hostPort.host();
        port = hostPort.port();
        if (port == -1) port = Integer.valueOf(ForgeNetPreferences.FNetPref.NET_PORT.getDefault());

        final FGameClient client = new FGameClient(FModel.getPreferences().getPref(FPref.PLAYER_NAME), gui, hostname, port);
        onlineLobby.setClient(client);
        chatInterface.setGameClient(client);
        final ClientGameLobby lobby = new ClientGameLobby();
        final ILobbyView view =  onlineLobby.setLobby(lobby);
        lobby.setListener(view);
        if (gui instanceof AbstractGuiGame agg) {
            agg.setClientLobby(lobby);
        }
        client.addLobbyListener(new ILobbyListener() {
            @Override
            public void message(final String source, final String message, final ChatMessage.MessageType type) {
                chatInterface.addMessage(new ChatMessage(source, message, type));
            }
            @Override
            public void update(final GameLobbyData state, final int slot) {
                lobby.setLocalPlayer(slot);
                lobby.setData(state);
            }
            @Override
            public void close() {
                onlineLobby.closeConn(Localizer.getInstance().getMessage("lblYourConnectionToHostWasInterrupted", url));
            }
            @Override
            public ClientGameLobby getLobby() {
                return lobby;
            }
        });
        client.setDraftHandler(view.getDraftHandler());
        view.setPlayerChangeListener((index, event) -> client.send(event));

        NetworkLogConfig.activateNetworkLogging();
        try {
            client.connect();
        }
        catch (Exception ex) {
            // Return error with details for GUI display
            String errorDetail = getConnectionErrorMessage(ex, hostname, port);
            return new ChatMessage(null, ForgeConstants.CONN_ERROR_PREFIX + errorDetail);
        }

        return new ChatMessage(null, Localizer.getInstance().getMessage("lblConnectedIPPort", hostname, String.valueOf(port)));
    }

    /**
     * Generate a user-friendly error message for connection failures.
     */
    private static String getConnectionErrorMessage(Exception ex, String hostname, int port) {
        Localizer localizer = Localizer.getInstance();
        StringBuilder sb = new StringBuilder();

        // Get the root cause for better error messages
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        String causeName = cause.getClass().getSimpleName();

        sb.append(localizer.getMessage("lblConnectionFailedTo", hostname, port));
        sb.append("\n\n");

        // Provide specific messages for common error types
        if (causeName.contains("ConnectException") || causeName.contains("ConnectionRefused")) {
            sb.append(localizer.getMessage("lblConnectionRefused"));
        } else if (causeName.contains("UnknownHost")) {
            sb.append(localizer.getMessage("lblUnknownHost"));
        } else if (causeName.contains("Timeout") || causeName.contains("TimedOut")) {
            sb.append(localizer.getMessage("lblConnectionTimeout"));
        } else if (causeName.contains("NoRouteToHost")) {
            sb.append(localizer.getMessage("lblNoRouteToHost"));
        } else {
            // Generic error with the exception message
            String msg = cause.getMessage();
            if (msg != null && !msg.isEmpty()) {
                sb.append(msg);
            } else {
                sb.append(causeName);
            }
        }

        return sb.toString();
    }
}
