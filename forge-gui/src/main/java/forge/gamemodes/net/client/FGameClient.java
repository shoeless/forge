package forge.gamemodes.net.client;

import com.google.common.collect.Lists;
import forge.game.player.PlayerView;
import forge.gamemodes.net.CompatibleObjectDecoder;
import forge.gamemodes.net.CompatibleObjectEncoder;
import forge.gamemodes.net.NetworkLogConfig;
import forge.util.IHasForgeLog;
import forge.gamemodes.net.ReplyPool;
import forge.gamemodes.net.event.*;
import forge.gui.interfaces.IDraftEventHandler;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.ILobbyListener;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class FGameClient implements IToServer, IHasForgeLog {

    static final int HEARTBEAT_INTERVAL_SECONDS = Integer.getInteger("forge.net.heartbeatInterval", 15);
    /** Same property/value as the server's: heartbeats are symmetric, so this client can detect a
     *  host that died without a TCP close (killed app, dropped cellular). Without this the guest
     *  sat frozen in a dead match forever — channelInactive never fires for a silent peer. */
    static final int HEARTBEAT_TIMEOUT_SECONDS = Integer.getInteger("forge.net.heartbeatTimeout", 45);
    private final IGuiGame clientGui;
    private final String hostname;
    private final Integer port;
    private final String username;
    private final List<ILobbyListener> lobbyListeners = Lists.newArrayList();
    private IDraftEventHandler draftHandler;
    private final ReplyPool replies = new ReplyPool();
    private volatile boolean disconnectSimulated;
    private volatile boolean closed;
    private Channel channel;

    public FGameClient(String username, IGuiGame clientGui, String hostname, int port) {
        this.username = username;
        this.clientGui = clientGui;
        this.hostname = hostname;
        this.port = port;
    }

    public String getUsername() {
        return username;
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
             .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(final SocketChannel ch) throws Exception {
                    final ChannelPipeline pipeline = ch.pipeline();
                    pipeline.addLast(
                            // DEBUG: with symmetric heartbeats an INFO wire log would hex-dump a
                            // heartbeat frame every 15s of idle (through os_log on iOS)
                            new LoggingHandler(LogLevel.DEBUG),
                            // IdleStateHandler must sit HEAD-side of the frame decoder: the decoder
                            // only emits COMPLETE frames, so if the idle timer sat behind it, a
                            // single large frame (e.g. the game-start state sync) streaming in
                            // slowly over cellular would never reset the read timer and a HEALTHY
                            // connection would be killed as "host gone". Raw socket reads reset the
                            // timer here; outbound writes still traverse it for writer-idle.
                            new IdleStateHandler(HEARTBEAT_TIMEOUT_SECONDS, HEARTBEAT_INTERVAL_SECONDS, 0, TimeUnit.SECONDS),
                            new CompatibleObjectEncoder(null), // Client doesn't need byte tracking
                            new CompatibleObjectDecoder(9766*1024, ClassResolvers.cacheDisabled(null)),
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
                    netLog.error(e, "Client channel interrupted");
                } finally {
                    group.shutdownGracefully();
                }
            }).start();
        } catch (final InterruptedException e) {
            netLog.error(e, "Client connect interrupted");
        }
    }

    public void close() {
        closed = true;
        replies.cancelAll(); //unblock any thread parked in sendAndWait
        if (channel != null)
            channel.close();
        NetworkLogConfig.deactivateNetworkLogging();
    }

    @Override
    public void send(final NetEvent event) {
        if (disconnectSimulated) {
            return;
        }
        netLog.info("Client sent {}", event);
        final CompatibleObjectEncoder encoder = channel.pipeline().get(CompatibleObjectEncoder.class);
        if (encoder == null) {
            netLog.error("No encoder in client pipeline for {}", event);
            return;
        }
        final ByteBuf encoded;
        try {
            encoded = encoder.encodeToBuf(event, channel.alloc());
        } catch (Exception e) {
            netLog.error(e, "Client encode error for {}", event);
            return;
        }
        channel.writeAndFlush(encoded);
    }

    /**
     * Simulate a crashed client: stop all network writes and heartbeats
     * while keeping the TCP connection open. The server's idle timeout
     * will detect the silence and close the connection.
     */
    public void simulateDisconnect() {
        netLog.info("[simulateDisconnect] Suspending all network writes.");
        disconnectSimulated = true;
        // Remove the IdleStateHandler to stop heartbeats, and add an outbound
        // handler that drops ALL writes (including game replies that bypass
        // send()). The TCP connection stays open but completely silent.
        // Both pipeline modifications run on the event loop thread for atomicity.
        channel.eventLoop().execute(() -> {
            channel.pipeline().remove(IdleStateHandler.class);
            channel.pipeline().addFirst("writeBlocker", new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                    netLog.info("[writeBlocker] Dropped: {}", msg.getClass().getSimpleName());
                    promise.setSuccess();
                }
            });
            netLog.info("[simulateDisconnect] Pipeline modified: IdleStateHandler removed, writeBlocker added.");
        });
    }

    @Override
    public Object sendAndWait(final IdentifiableNetEvent event) {
        // After a disconnect no reply can ever arrive — blocking here froze the app for good.
        if (closed) {
            return null;
        }
        replies.initialize(event.getId());
        if (closed) {
            // Disconnected between the check above and initialize: the cancelAll that unblocks
            // waiters may already have run, so this future would never complete. Bail out.
            return null;
        }
        send(event);

        // Wait for reply (released with null by cancelAll on disconnect)
        return replies.get(event.getId());
    }

    List<ILobbyListener> getLobbyListeners() {
        return lobbyListeners;
    }

    public void addLobbyListener(final ILobbyListener listener) {
        lobbyListeners.add(listener);
    }

    public void setDraftHandler(final IDraftEventHandler handler) {
        this.draftHandler = handler;
    }

    void setGameControllers(final Iterable<PlayerView> myPlayers) {
        for (final PlayerView p : myPlayers) {
            NetGameController controller = new NetGameController(this);
            clientGui.setOriginalGameController(p, controller);
        }
    }

    private class MessageHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            if (msg instanceof HeartbeatEvent) {
                return; // Consumed — arrival reset IdleStateHandler's read timer (mirror of the server)
            }
            if (msg instanceof MessageEvent event) {
                for (final ILobbyListener listener : lobbyListeners) {
                    listener.message(event.getSource(), event.getMessage(), event.getType());
                }
            }
            super.channelRead(ctx, msg);
        }
    }

    private class LobbyUpdateHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            if (msg instanceof LobbyUpdateEvent event) {
                for (final ILobbyListener listener : lobbyListeners) {
                    listener.update(event.getState(), event.getSlot());
                }
            } else if (msg instanceof NetEvent netEvent && draftHandler != null
                    && draftHandler.dispatch(netEvent)) {
                return;
            }
            super.channelRead(ctx, msg);
        }

        @Override
        public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
            if (evt instanceof IdleStateEvent ise && ise.state() == IdleState.WRITER_IDLE) {
                ctx.writeAndFlush(new HeartbeatEvent());
            }
            if (evt instanceof IdleStateEvent ise && ise.state() == IdleState.READER_IDLE) {
                // Nothing (not even a heartbeat) from the host for the full timeout: the host is
                // gone without a TCP close (killed app, dropped cellular). Close the channel so
                // channelInactive runs the normal disconnect path instead of freezing forever.
                netLog.warn("[Disconnect] No data from host for {}s — treating the host as gone",
                        HEARTBEAT_TIMEOUT_SECONDS);
                ctx.close();
                return;
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
            netLog.info("[Disconnect] Channel became inactive, notifying {} listeners", lobbyListeners.size());
            netLog.info("[Disconnect] Remote address was: {}", ctx.channel().remoteAddress());
            // Release any thread parked in sendAndWait BEFORE notifying listeners: no reply can
            // ever arrive now, and a blocked game/UI thread would freeze the app for good.
            closed = true;
            replies.cancelAll();
            for (final ILobbyListener listener : lobbyListeners) {
                listener.close();
            }
            super.channelInactive(ctx);
        }
    }
}
