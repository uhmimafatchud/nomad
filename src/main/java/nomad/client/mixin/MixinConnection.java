package nomad.client.mixin;

import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.SocketChannel;
import com.mojang.logging.LogUtils;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import nomad.client.proxy.ProxyEntry;
import nomad.client.proxy.ProxyManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;

/** Splices the active proxy's Netty handler into the connection pipeline right after it's built.
 *  1.19.4's {@code configureSerialization} has no "is this a local/integrated-server pipe" flag
 *  like newer versions do, so a real remote socket is identified by channel type instead. */
@Mixin(Connection.class)
public class MixinConnection {
    private static final Logger NOMAD_LOG = LogUtils.getLogger();

    @Inject(method = "configureSerialization", at = @At("RETURN"))
    private static void nomad$addProxyHandler(ChannelPipeline pipeline, PacketFlow side, CallbackInfo ci) {
        ProxyEntry entry = ProxyManager.INSTANCE.getActiveEntry();
        if (entry != null && side == PacketFlow.CLIENTBOUND && pipeline.channel() instanceof SocketChannel) {
            pipeline.addFirst(ProxyManager.newProxyHandler(entry));
            pipeline.addLast("nomad-proxy-errors", new ChannelInboundHandlerAdapter() {
                @Override
                public void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception {
                    NOMAD_LOG.warn("[Nomad/Proxy] Connection through {} failed: {}",
                            entry.label(), cause.toString());
                    super.exceptionCaught(context, cause);
                }
            });
        }
    }
}
