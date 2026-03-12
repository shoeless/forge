package forge.gamemodes.net;

import forge.gui.GuiBase;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.serialization.ClassResolver;

import java.io.ObjectInputStream;
import java.io.StreamCorruptedException;
import java.util.zip.GZIPInputStream;

public class CompatibleObjectDecoder extends LengthFieldBasedFrameDecoder {
    private final ClassResolver classResolver;

    public CompatibleObjectDecoder(ClassResolver classResolver) {
        this(1048576, classResolver);
    }

    public CompatibleObjectDecoder(int maxObjectSize, ClassResolver classResolver) {
        super(maxObjectSize, 0, 4, 0, 4);
        this.classResolver = classResolver;
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        ByteBuf frame = (ByteBuf)super.decode(ctx, in);
        if (frame == null) {
            return null;
        }
        GZIPInputStream gzipIn = new GZIPInputStream(new ByteBufInputStream(frame, true));
        ObjectInputStream ois = GuiBase.hasPropertyConfig() ?
                new ObjectInputStream(gzipIn) :
                new CObjectInputStream(gzipIn, this.classResolver);

        Object var5 = null;
        long decodeStart = System.currentTimeMillis();
        try {
            var5 = ois.readObject();
        } catch (StreamCorruptedException e) {
            System.err.printf("[ERR] NET DECODER: Version Mismatch: %s%n", e.getMessage());
        } catch (Exception e) {
            System.err.println("[ERR] NET DECODER: Error decoding object: " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
        } finally {
            ois.close();
        }
        long decodeElapsed = System.currentTimeMillis() - decodeStart;
        int frameSize = frame.readableBytes();
        String objName = var5 != null ? var5.getClass().getSimpleName() : "null";
        if (decodeElapsed > 10) {
            System.err.println("[ERR] NET DECODER: " + objName + " " + frameSize + " bytes in " + decodeElapsed + "ms");
        }

        return var5;
    }
}
