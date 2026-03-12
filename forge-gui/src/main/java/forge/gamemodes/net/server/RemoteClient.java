package forge.gamemodes.net.server;

import forge.gamemodes.net.CObjectOutputStream;
import forge.gamemodes.net.ReplyPool;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gamemodes.net.event.IdentifiableNetEvent;
import forge.gamemodes.net.event.NetEvent;
import forge.gui.GuiBase;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPOutputStream;

public final class RemoteClient implements IToClient {

    private final Channel channel;
    private String username;
    private int index;
    private ReplyPool replies = new ReplyPool();
    public RemoteClient(final Channel channel) {
        this.channel = channel;
    }

    @Override
    public void send(final NetEvent event) {
        if (channel.eventLoop().inEventLoop()) {
            // Already on the event loop (e.g., called from channelActive/channelRead
            // handlers). Write directly to avoid deadlock.
            channel.writeAndFlush(event);
        } else {
            // Game thread: pre-serialize to byte[] here, then write the raw
            // bytes async. The game thread only blocks for serialization
            // (microseconds), NOT for the network write.
            try {
                long startMs = System.currentTimeMillis();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ObjectOutputStream oos = GuiBase.hasPropertyConfig()
                    ? new ObjectOutputStream(new GZIPOutputStream(baos))
                    : new CObjectOutputStream(new GZIPOutputStream(baos));
                oos.writeObject(event);
                oos.close();
                byte[] serialized = baos.toByteArray();
                long elapsed = System.currentTimeMillis() - startMs;

                String detail = event.getClass().getSimpleName();
                if (event instanceof GuiGameEvent) {
                    GuiGameEvent gge = (GuiGameEvent) event;
                    detail += " method=" + gge.getMethod().name() + " args=" + gge.getObjects().length;
                }
                System.err.println("[ERR] NET SEND: " + detail + " " + serialized.length + " bytes (" + elapsed + "ms)");

                // ByteBuf bypasses CompatibleObjectEncoder (it only encodes
                // Serializable, and ByteBuf is not Serializable). The decoder's
                // LengthFieldBasedFrameDecoder reads the 4-byte length prefix
                // and GZIP payload correctly.
                ByteBuf buf = channel.alloc().buffer(4 + serialized.length);
                buf.writeInt(serialized.length);
                buf.writeBytes(serialized);
                channel.writeAndFlush(buf);
            } catch (Exception e) {
                System.err.println("RemoteClient: Failed to serialize event: "
                        + e.getClass().getName() + " - " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    @Override
    public Object sendAndWait(final IdentifiableNetEvent event) throws TimeoutException {
        replies.initialize(event.getId());

        send(event);

        return replies.get(event.getId());
    }

    public String getUsername() {
        return username;
    }
    public void setUsername(final String username) {
        this.username = username;
    }

    public int getIndex() {
        return index;
    }
    public void setIndex(final int index) {
        this.index = index;
    }

    ReplyPool getReplyPool() {
        return replies;
    }
}
