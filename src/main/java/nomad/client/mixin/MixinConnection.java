package nomad.client.mixin;

import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import net.minecraft.network.BandwidthDebugMonitor;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import nomad.client.proxy.ProxyEntry;
import nomad.client.proxy.ProxyManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Splices the active proxy's Netty handler into the connection pipeline right after it's built.
 *  Local (singleplayer/integrated-server) connections go through a separate
 *  {@code configureInMemoryPipeline} method entirely, so a real remote socket is identified by
 *  channel type rather than trusting the meaning of this method's boolean parameter. */
@Mixin(Connection.class)
public class MixinConnection {

    @Inject(method = "configureSerialization", at = @At("RETURN"))
    private static void nomad$addProxyHandler(ChannelPipeline pipeline, PacketFlow side, boolean flag,
                                               BandwidthDebugMonitor packetSizeLogger, CallbackInfo ci) {
        ProxyEntry entry = ProxyManager.INSTANCE.getActiveEntry();
        if (entry != null && side == PacketFlow.CLIENTBOUND && pipeline.channel() instanceof SocketChannel)
            pipeline.addFirst(ProxyManager.newProxyHandler(entry));
    }
}
