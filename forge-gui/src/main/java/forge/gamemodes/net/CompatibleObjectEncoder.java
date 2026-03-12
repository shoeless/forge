package forge.gamemodes.net;

import forge.gamemodes.net.event.GuiGameEvent;
import forge.gui.GuiBase;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.zip.GZIPOutputStream;

public class CompatibleObjectEncoder extends MessageToByteEncoder<Serializable> {
    private static final byte[] LENGTH_PLACEHOLDER = new byte[4];

    @Override
    protected void encode(ChannelHandlerContext ctx, Serializable msg, ByteBuf out) throws Exception {
        String detail = msg.getClass().getSimpleName();
        if (msg instanceof GuiGameEvent) {
            GuiGameEvent gge = (GuiGameEvent) msg;
            detail += " method=" + gge.getMethod().name() + " args=" + gge.getObjects().length;
        }
        int startIdx = out.writerIndex();
        ByteBufOutputStream bout = new ByteBufOutputStream(out);
        ObjectOutputStream oout = null;

        try {
            bout.write(LENGTH_PLACEHOLDER);
            GZIPOutputStream gzipOut = new GZIPOutputStream(bout);
            oout = GuiBase.hasPropertyConfig() ? new ObjectOutputStream(gzipOut) : new CObjectOutputStream(gzipOut);
            oout.writeObject(msg);
            oout.flush();
            oout.close();
            oout = null;
        } finally {
            if (oout != null) {
                oout.close();
            } else {
                bout.close();
            }
        }

        int endIdx = out.writerIndex();
        int encodedLength = endIdx - startIdx - 4;
        out.setInt(startIdx, encodedLength);
        System.err.println("[ERR] NET ENCODER: " + detail + " " + encodedLength + " bytes");
    }
}
