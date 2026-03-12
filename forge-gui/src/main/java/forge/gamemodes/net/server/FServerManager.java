package forge.gamemodes.net.server;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.CompatibleObjectDecoder;
import forge.gamemodes.net.CompatibleObjectEncoder;
import forge.gamemodes.net.HeartbeatHandler;
import forge.gamemodes.net.event.*;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.gui.util.SOptionPane;
import forge.interfaces.IGameController;
import forge.interfaces.ILobbyListener;
import forge.model.FModel;
import forge.util.IterableUtil;
import forge.util.Localizer;
import forge.localinstance.properties.ForgeNetPreferences;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;

import org.jupnp.UpnpService;
import org.jupnp.UpnpServiceImpl;
import org.jupnp.support.igd.PortMappingListener;
import org.jupnp.support.model.PortMapping;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.util.*;
import forge.util.function.Predicate;

public final class FServerManager {
    private static FServerManager instance = null;
    private final Map<Channel, RemoteClient> clients = Maps.newTreeMap();
    private boolean isHosting = false;
    private EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private EventLoopGroup workerGroup = new NioEventLoopGroup();
    private UpnpService upnpService = null;
    private ServerBroadcaster broadcaster;
    private ServerGameLobby localLobby;
    private ILobbyListener lobbyListener;
    private boolean UPnPMapped = false;
    private int port;
    private static final Localizer localizer = Localizer.getInstance();
    private final Thread shutdownHook = new Thread(() -> {
        if (isHosting()) {
            stopServer(false);
        }
    });

    private FServerManager() {
    }


    RemoteClient getClient(final Channel ch) {
        return clients.get(ch);
    }

    IGameController getController(final int index) {
        return localLobby.getController(index);
    }

    /**
     * Get the singleton instance of {@link FServerManager}.
     *
     * @return the singleton FServerManager.
     */
    public static FServerManager getInstance() {
        if (instance == null) {
            instance = new FServerManager();
        }
        return instance;
    }

    public void startServer(final int port) {
        this.port = port;
        // Skip UPnP on iOS - jupnp library is not available
        boolean startUPnP = false;
        if (!GuiBase.isIOS()) {
            String UPnPOption = FModel.getNetPreferences().getPref(ForgeNetPreferences.FNetPref.UPnP);
            if (UPnPOption.equalsIgnoreCase("ASK")) {
                startUPnP = callUPnPDialog();
            } else {
                startUPnP = UPnPOption.equalsIgnoreCase("ALWAYS");
            }
        }
        System.out.println("Starting Multiplayer Server");
        try {
            final ServerBootstrap b = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(final SocketChannel ch) throws Exception {
                            final ChannelPipeline p = ch.pipeline();
                            p.addLast(
                                    new IdleStateHandler(60, 10, 0, java.util.concurrent.TimeUnit.SECONDS),
                                    new CompatibleObjectEncoder(),
                                    new CompatibleObjectDecoder(9766 * 1024, ClassResolvers.cacheDisabled(null)),
                                    new HeartbeatHandler(),
                                    new MessageHandler(),
                                    new RegisterClientHandler(),
                                    new LobbyInputHandler(),
                                    new DeregisterClientHandler(),
                                    new GameServerHandler());
                        }
                    });

            // Try IPv6 dual-stack bind first (accepts both IPv4 and IPv6),
            // fall back to IPv4-only if it fails (e.g. RoboVM on iOS).
            ChannelFuture bindFuture;
            try {
                bindFuture = b.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port)).sync();
            } catch (Exception e) {
                System.err.println("Bind failed: " + e.getMessage());
                throw e instanceof InterruptedException ? (InterruptedException) e : new InterruptedException(e.getMessage());
            }
            final ChannelFuture ch = bindFuture.channel().closeFuture();
            new Thread(() -> {
                try {
                    ch.sync();
                } catch (final InterruptedException e) {
                    System.out.println(e.getMessage());
                    e.printStackTrace();
                } finally {
                    stopServer();
                }
            }).start();
            if(startUPnP) {
                mapNatPort();
            }
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            isHosting = true;

            // Start LAN broadcast for server discovery
            String playerName = FModel.getPreferences().getPref(
                    forge.localinstance.properties.ForgePreferences.FPref.PLAYER_NAME);
            if (playerName == null || playerName.isEmpty()) {
                playerName = "Host";
            }
            broadcaster = new ServerBroadcaster(playerName, port, 4);
            String tailscaleApiKey = FModel.getNetPreferences().getPref(
                    ForgeNetPreferences.FNetPref.TAILSCALE_API_KEY);
            if (tailscaleApiKey != null && !tailscaleApiKey.isEmpty()) {
                broadcaster.setTailscaleApiKey(tailscaleApiKey);
                System.out.println("ServerBroadcaster: Tailscale Cloud API key configured");
            }
            broadcaster.start();
        } catch (final InterruptedException e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean callUPnPDialog() {
        switch (SOptionPane.showOptionDialog(localizer.getMessageorUseDefault("lblUPnPQuestion", String.format("Attempt to open port %d automatically?", port), port),
                localizer.getMessageorUseDefault("lblUPnPTitle", "UPnP option"),
                null,
                ImmutableList.of(localizer.getMessageorUseDefault("lblJustOnce", "Just Once"),
                        localizer.getMessageorUseDefault("lblNotNow", "Not Now"),
                        localizer.getMessageorUseDefault("lblAlways", "Always"),
                        localizer.getMessageorUseDefault("lblNever", "Never")), 0)) {
            case 2:
                FModel.getNetPreferences().setPref(ForgeNetPreferences.FNetPref.UPnP, "ALWAYS");
                FModel.getNetPreferences().save();
            case 0:
                //case 2 falls in here
                return true;
            case 3:
                FModel.getNetPreferences().setPref(ForgeNetPreferences.FNetPref.UPnP, "NEVER");
                FModel.getNetPreferences().save();
            default:
                //case 1 defaults to here
                return false;
        }
    }

    public void stopServer() {
        stopServer(true);
    }

    private void stopServer(final boolean removeShutdownHook) {
        if (broadcaster != null) {
            broadcaster.stop();
            broadcaster = null;
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        if (upnpService != null) {
            upnpService.shutdown();
            upnpService = null;
        }
        if (removeShutdownHook) {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        }
        isHosting = false;
        // create new EventLoopGroups for potential restart
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
    }

    public boolean isHosting() {
        return isHosting;
    }

    public void broadcast(final NetEvent event) {
        if (event instanceof MessageEvent) {
            MessageEvent msgEvent = (MessageEvent) event;
            lobbyListener.message(msgEvent.getSource(), msgEvent.getMessage());
        }
        broadcastTo(event, clients.values());
    }

    public void broadcastExcept(final NetEvent event, final RemoteClient notTo) {
        broadcastExcept(event, Collections.singleton(notTo));
    }

    public void broadcastExcept(final NetEvent event, final Collection<RemoteClient> notTo) {
        Predicate<RemoteClient> filter = client -> !notTo.contains(client);
        broadcastTo(event, IterableUtil.filter(clients.values(), filter));
    }

    private void broadcastTo(final NetEvent event, final Iterable<RemoteClient> to) {
        for (final RemoteClient client : to) {
            broadcastTo(event, client);
        }
    }

    private void broadcastTo(final NetEvent event, final RemoteClient to) {
        event.updateForClient(to);
        to.send(event);
    }

    public void setLobby(final ServerGameLobby lobby) {
        this.localLobby = lobby;
    }

    public void unsetReady() {
        if (this.localLobby != null && this.localLobby.getSlot(0) != null) {
                this.localLobby.getSlot(0).setIsReady(false);
                updateLobbyState();
        }
    }

    public boolean isMatchActive() {
        return this.localLobby != null && this.localLobby.isMatchActive();
    }

    public void setLobbyListener(final ILobbyListener listener) {
        this.lobbyListener = listener;
    }

    public void updateLobbyState() {
        final LobbyUpdateEvent event = new LobbyUpdateEvent(localLobby.getData());
        broadcast(event);
    }

    public void updateSlot(final int index, final UpdateLobbyPlayerEvent event) {
        localLobby.applyToSlot(index, event);
    }

    public IGuiGame getGui(final int index) {
        final LobbySlot slot = localLobby.getSlot(index);
        final LobbySlotType type = slot.getType();
        if (type == LobbySlotType.LOCAL) {
            return GuiBase.getInterface().getNewGuiGame();
        } else if (type == LobbySlotType.REMOTE) {
            for (final RemoteClient client : clients.values()) {
                if (client.getIndex() == index) {
                    return new NetGuiGame(client);
                }
            }
        }
        return null;
    }

    // inspired by:
    //  https://stackoverflow.com/a/34873630
    //  https://stackoverflow.com/a/901943
    private static String getRoutableAddress(boolean preferIpv4, boolean preferIPv6) throws SocketException, UnknownHostException {
        try (DatagramSocket socket = new DatagramSocket()) {
            // Connect to a well-known external address (Google's public DNS server)
            socket.connect(InetAddress.getByName("8.8.8.8"), 12345); // Use a valid port instead of 0
            InetAddress localAddress = socket.getLocalAddress();

            // Check if the local address belongs to a valid network interface
            NetworkInterface networkInterface = NetworkInterface.getByInetAddress(localAddress);
            if (networkInterface != null && networkInterface.isUp()) {
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && preferIpv4) {
                        return addr.getHostAddress();
                    }
                    if (addr instanceof Inet6Address && preferIPv6) {
                        return addr.getHostAddress();
                    }
                }
            }
        }
        return "localhost";
    }

    /**
     * Returns all non-VPN, non-loopback IPv4 LAN addresses on this machine.
     */
    public static List<String> getAllLanAddresses() {
        List<String> result = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();
                    System.out.println("NET: Interface " + ni.getName()
                            + " up=" + ni.isUp() + " loopback=" + ni.isLoopback());
                    if (!ni.isUp() || ni.isLoopback()) {
                        continue;
                    }
                    // Skip VPN tunnel interfaces
                    String name = ni.getName().toLowerCase();
                    if (name.startsWith("tun") || name.startsWith("utun")
                            || name.startsWith("ppp") || name.startsWith("tap")) {
                        continue;
                    }
                    Enumeration<InetAddress> addresses = ni.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        System.out.println("NET:   " + ni.getName() + " -> " + addr.getHostAddress()
                                + " (IPv4=" + (addr instanceof Inet4Address) + ")");
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            result.add(addr.getHostAddress());
                        }
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println("Failed to enumerate network interfaces: " + e.getMessage());
        }
        System.out.println("NET: getAllLanAddresses result: " + result);
        return result;
    }

    /**
     * Returns all non-loopback, non-link-local addresses on this machine,
     * including both IPv4 and IPv6. Unlike getAllLanAddresses(), this does NOT
     * skip VPN/tunnel interfaces — we want Tailscale addresses for discovery.
     * IPv6 addresses are listed first to encourage direct connections.
     */
    public static List<String> getAllAddressesForDiscovery() {
        List<String> ipv6 = new ArrayList<String>();
        List<String> ipv4 = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();
                    if (!ni.isUp() || ni.isLoopback()) {
                        continue;
                    }
                    Enumeration<InetAddress> addresses = ni.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) {
                            continue;
                        }
                        String hostAddr = addr.getHostAddress();
                        // Strip IPv6 scope ID (e.g. "%en0") if present
                        int scopeIdx = hostAddr.indexOf('%');
                        if (scopeIdx >= 0) {
                            hostAddr = hostAddr.substring(0, scopeIdx);
                        }
                        if (addr instanceof Inet6Address) {
                            ipv6.add(hostAddr);
                        } else if (addr instanceof Inet4Address) {
                            ipv4.add(hostAddr);
                        }
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println("Failed to enumerate network interfaces: " + e.getMessage());
        }
        // IPv6 first — better chance of direct connection over Tailscale
        List<String> result = new ArrayList<String>(ipv6.size() + ipv4.size());
        result.addAll(ipv6);
        result.addAll(ipv4);
        System.out.println("NET: getAllAddressesForDiscovery: " + result);
        return result;
    }

    public static String getLocalAddress() {
        // Prefer LAN addresses (skips VPN interfaces)
        List<String> lanAddresses = getAllLanAddresses();
        if (!lanAddresses.isEmpty()) {
            return lanAddresses.get(0);
        }
        // Fall back to routable address detection
        try {
            return getRoutableAddress(true, false);
        } catch (final Exception e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
            return "localhost";
        }
    }

    public static String getExternalAddress() {
        BufferedReader in = null;
        try {
            URL whatismyip = new URL("https://checkip.amazonaws.com");
            in = new BufferedReader(new InputStreamReader(
                    whatismyip.openStream()));
            return in.readLine();
        } catch (IOException e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    System.out.println(e.getMessage());
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    private void mapNatPort() {
        final String localAddress = getLocalAddress();
        final PortMapping portMapping = new PortMapping(port, localAddress, PortMapping.Protocol.TCP, "Forge");
        // Shutdown existing UPnP service if already running
        if (upnpService != null) {
            upnpService.shutdown();
        }

        try {
            // Create a new UPnP service instance
            upnpService = new UpnpServiceImpl(GuiBase.getInterface().getUpnpPlatformService());
            upnpService.startup();

            // Add a PortMappingListener
            upnpService.getRegistry().addListener(new PortMappingListener(portMapping));
            // Trigger device discovery
            upnpService.getControlPoint().search();
        } catch (Exception e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
        }
    }

    private class MessageHandler extends ChannelInboundHandlerAdapter {
        @Override
        public final void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            final RemoteClient client = clients.get(ctx.channel());
            if (msg instanceof MessageEvent) {
                broadcast(new MessageEvent(client.getUsername(), ((MessageEvent) msg).getMessage()));
            }
            super.channelRead(ctx, msg);
        }
    }

    private class RegisterClientHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(final ChannelHandlerContext ctx) throws Exception {
            final RemoteClient client = new RemoteClient(ctx.channel());
            clients.put(ctx.channel(), client);
            System.out.println("SERVER: Client connected from " + ctx.channel().remoteAddress());
            updateLobbyState();
            super.channelActive(ctx);
        }

        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            System.out.println("SERVER RegisterClientHandler: Received message type=" + (msg != null ? msg.getClass().getName() : "null"));
            final RemoteClient client = clients.get(ctx.channel());
            if (msg instanceof LoginEvent) {
                final String username = ((LoginEvent) msg).getUsername();
                System.out.println("SERVER: LoginEvent received from " + username);
                client.setUsername(username);
                broadcast(new MessageEvent(String.format("%s joined the room", username)));
                updateLobbyState();
            } else if (msg instanceof UpdateLobbyPlayerEvent) {
                localLobby.applyToSlot(client.getIndex(), (UpdateLobbyPlayerEvent) msg);
            }
            super.channelRead(ctx, msg);
        }
    }

    private class LobbyInputHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            System.out.println("SERVER LobbyInputHandler: Received message type=" + (msg != null ? msg.getClass().getName() : "null"));
            final RemoteClient client = clients.get(ctx.channel());
            if (msg instanceof LoginEvent) {
                final LoginEvent event = (LoginEvent) msg;
                System.out.println("SERVER LobbyInputHandler: Processing LoginEvent for " + event.getUsername());
                final int index = localLobby.connectPlayer(event.getUsername(), event.getAvatarIndex(), event.getSleeveIndex());
                System.out.println("SERVER LobbyInputHandler: Player assigned to slot " + index);
                if (index == -1) {
                    System.out.println("SERVER LobbyInputHandler: No slot available, closing connection");
                    ctx.close();
                } else {
                    client.setIndex(index);
                    broadcast(event);
                    updateLobbyState();
                    System.out.println("SERVER LobbyInputHandler: Lobby state updated and broadcast");
                }
            } else if (msg instanceof UpdateLobbyPlayerEvent) {
                updateSlot(client.getIndex(), (UpdateLobbyPlayerEvent) msg);
                updateLobbyState(); // Broadcast changes to all clients
            } else if (msg instanceof MessageEvent) {
                final MessageEvent event = (MessageEvent) msg;
                lobbyListener.message(event.getSource(), event.getMessage());
            }
            super.channelRead(ctx, msg);
        }
    }

    private class DeregisterClientHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
            final RemoteClient client = clients.remove(ctx.channel());
            final String username = client.getUsername();
            localLobby.disconnectPlayer(client.getIndex());
            broadcast(new MessageEvent(String.format("%s left the room", username)));
            broadcast(new LogoutEvent(username));
            super.channelInactive(ctx);
        }
    }
}
