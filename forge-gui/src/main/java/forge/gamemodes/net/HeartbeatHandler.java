package forge.gamemodes.net;

import forge.gamemodes.net.event.HeartbeatEvent;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

public class HeartbeatHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            if (e.state() == IdleState.WRITER_IDLE) {
                ctx.writeAndFlush(new HeartbeatEvent());
            } else if (e.state() == IdleState.READER_IDLE) {
                System.out.println("NET: Connection idle timeout, closing: " + ctx.channel().remoteAddress());
                ctx.close();
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (msg instanceof HeartbeatEvent) {
            // Consume heartbeat silently, don't pass to game handlers
            return;
        }
        super.channelRead(ctx, msg);
    }
}
