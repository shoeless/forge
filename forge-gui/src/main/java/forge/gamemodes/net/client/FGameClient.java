package forge.gamemodes.net.client;

import com.google.common.collect.Lists;
import forge.game.player.PlayerView;
import forge.gamemodes.net.CompatibleObjectDecoder;
import forge.gamemodes.net.CompatibleObjectEncoder;
import forge.gamemodes.net.HeartbeatHandler;
import forge.gamemodes.net.ReplyPool;
import forge.gamemodes.net.event.IdentifiableNetEvent;
import forge.gamemodes.net.event.LobbyUpdateEvent;
import forge.gamemodes.net.event.MessageEvent;
import forge.gamemodes.net.event.NetEvent;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.ILobbyListener;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class FGameClient implements IToServer {
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private final IGuiGame clientGui;
    private final String hostname;
    private final Integer port;
    private final List<ILobbyListener> lobbyListeners = Lists.newArrayList();
    private final ReplyPool replies = new ReplyPool();
    private Channel channel;
    private volatile boolean reconnecting = false;

    public FGameClient(String username, String roomKey, IGuiGame clientGui, String hostname, int port) {
        this.clientGui = clientGui;
        this.hostname = hostname;
        this.port = port;
    }

    final IGuiGame getGui() {
        return clientGui;
    }
    final ReplyPool getReplyPool() {
        return replies;
    }

    public void connect() {
        final EventLoopGroup group = new NioEventLoopGroup();
        try {
            final Bootstrap b = new Bootstrap()
             .group(group)
             .channel(NioSocketChannel.class)
             .option(ChannelOption.TCP_NODELAY, true)
             .option(ChannelOption.SO_KEEPALIVE, true)
             .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
             .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(final SocketChannel ch) throws Exception {
                    final ChannelPipeline pipeline = ch.pipeline();
                    pipeline.addLast(
                            new IdleStateHandler(60, 10, 0, TimeUnit.SECONDS),
                            new CompatibleObjectEncoder(),
                            new CompatibleObjectDecoder(9766*1024, ClassResolvers.cacheDisabled(null)),
                            new HeartbeatHandler(),
                            new MessageHandler(),
                            new LobbyUpdateHandler(),
                            new GameClientHandler(FGameClient.this));
                }
             });

            // Start the connection attempt.
            channel = b.connect(this.hostname, this.port).sync().channel();
            final ChannelFuture ch = channel.closeFuture();
            new Thread(() -> {
                try {
                    ch.sync();
                } catch (final InterruptedException e) {
                    System.out.println(e.getMessage());
                    e.printStackTrace();
                } finally {
                    group.shutdownGracefully();
                }
            }).start();
        } catch (final InterruptedException e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
        }
    }

    public void close() {
        if (channel != null)
            channel.close();
    }

    private void reconnect() throws InterruptedException {
        final EventLoopGroup group = new NioEventLoopGroup();
        final Bootstrap b = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(final SocketChannel ch) throws Exception {
                        final ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(
                                new IdleStateHandler(60, 10, 0, TimeUnit.SECONDS),
                                new CompatibleObjectEncoder(),
                                new CompatibleObjectDecoder(9766 * 1024,
                                        ClassResolvers.cacheDisabled(null)),
                                new HeartbeatHandler(),
                                new MessageHandler(),
                                new LobbyUpdateHandler(),
                                new GameClientHandler(FGameClient.this));
                    }
                });

        channel = b.connect(hostname, port).sync().channel();
        final ChannelFuture ch = channel.closeFuture();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ch.sync();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    group.shutdownGracefully();
                }
            }
        }).start();
        // GameClientHandler.channelActive() sends LoginEvent automatically
    }

    @Override
    public void send(final NetEvent event) {
        channel.writeAndFlush(event);
    }

    @Override
    public Object sendAndWait(final IdentifiableNetEvent event) throws TimeoutException {
        replies.initialize(event.getId());

        send(event);

        // Wait for reply
        return replies.get(event.getId());
    }

    List<ILobbyListener> getLobbyListeners() {
        return lobbyListeners;
    }

    public void addLobbyListener(final ILobbyListener listener) {
        lobbyListeners.add(listener);
    }

    void setGameControllers(final Iterable<PlayerView> myPlayers) {
        for (final PlayerView p : myPlayers) {
            clientGui.setOriginalGameController(p, new NetGameController(this));
        }
    }

    private class MessageHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            if (msg instanceof MessageEvent) {
                final MessageEvent event = (MessageEvent) msg;
                for (final ILobbyListener listener : lobbyListeners) {
                    listener.message(event.getSource(), event.getMessage());
                }
            }
            super.channelRead(ctx, msg);
        }
    }

    private class LobbyUpdateHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            if (msg instanceof LobbyUpdateEvent) {
                final LobbyUpdateEvent event = (LobbyUpdateEvent) msg;
                for (final ILobbyListener listener : lobbyListeners) {
                    listener.update(event.getState(), event.getSlot());
                }
            }
            super.channelRead(ctx, msg);
        }

        @Override
        public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
            if (reconnecting) {
                // Old channel closing during reconnect, ignore
                super.channelInactive(ctx);
                return;
            }

            System.err.println("[ERR] NET: Connection lost, attempting reconnection...");
            reconnecting = true;

            new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int attempt = 1; attempt <= 10; attempt++) {
                        try {
                            long delay = Math.min(1000L * attempt, 5000L);
                            Thread.sleep(delay);
                            System.err.println("[ERR] NET: Reconnect attempt " + attempt);
                            reconnect();
                            System.err.println("[ERR] NET: Reconnected successfully");
                            reconnecting = false;
                            return;
                        } catch (Exception e) {
                            System.err.println("[ERR] NET: Reconnect attempt " + attempt
                                    + " failed: " + e.getMessage());
                        }
                    }
                    System.err.println("[ERR] NET: All reconnection attempts failed");
                    reconnecting = false;
                    for (final ILobbyListener listener : lobbyListeners) {
                        listener.close();
                    }
                }
            }).start();

            super.channelInactive(ctx);
        }
    }
}
