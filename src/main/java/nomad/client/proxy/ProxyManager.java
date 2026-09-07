package nomad.client.proxy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Owns the proxy pool: persistence, latency testing, and building the Netty handler that
 *  {@link nomad.client.mixin.MixinConnection} splices into the connection to the server. */
public final class ProxyManager {
    public static final ProxyManager INSTANCE = new ProxyManager();

    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson   GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String TEST_HOST = "1.1.1.1";   // CONNECT target for latency tests
    private static final int    TEST_PORT = 443;
    private static final int    TEST_TIMEOUT_S = 6;

    private final List<ProxyEntry> pool = new ArrayList<>();
    private ProxyEntry activeEntry;

    private ProxyManager() {}

    private static Path poolFile() {
        return FabricLoader.getInstance().getGameDir().resolve("nomad").resolve("proxies.json");
    }

    // ── Pool access ───────────────────────────────────────────────────────────

    public List<ProxyEntry> getPool() { return pool; }
    public ProxyEntry getActiveEntry() { return activeEntry; }

    public void addEntry(ProxyEntry e) {
        pool.add(e);
        save();
    }

    public void removeEntry(ProxyEntry e) {
        pool.remove(e);
        if (activeEntry == e) activeEntry = null;
        save();
    }

    /** Exclusive activation — clears every other entry's active flag. Pass null to go direct. */
    public void setActiveEntry(ProxyEntry e) {
        activeEntry = e;
        for (ProxyEntry p : pool) p.active = (p == e);
        save();
    }

    // ── Real proxy testing (off-thread, per-protocol) ─────────────────────────

    public void testAll() {
        for (ProxyEntry e : pool) testEntry(e);
    }

    /** Open a real connection through the proxy to {@link #TEST_HOST}, measure latency. */
    public void testEntry(ProxyEntry e) {
        if (e == null) return;
        e.status = ProxyEntry.Status.TESTING;
        e.pingMs = -1;
        final long start = System.currentTimeMillis();
        new Thread(() -> {
            NioEventLoopGroup group = new NioEventLoopGroup();
            try {
                Bootstrap bootstrap = new Bootstrap();
                bootstrap.group(group)
                        .channel(NioSocketChannel.class)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ch.pipeline()
                                        .addLast(new ReadTimeoutHandler(TEST_TIMEOUT_S))
                                        .addLast(newProxyHandler(e))
                                        .addLast(new ChannelInboundHandlerAdapter() {
                                            @Override
                                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                                ctx.close();
                                            }
                                        });
                            }
                        });
                ChannelFuture f = bootstrap.connect(TEST_HOST, TEST_PORT).sync();
                f.await(TEST_TIMEOUT_S + 2L, TimeUnit.SECONDS);
                if (f.isSuccess()) {
                    e.pingMs = (int) (System.currentTimeMillis() - start);
                    e.status = ProxyEntry.Status.ALIVE;
                } else {
                    e.status = ProxyEntry.Status.DEAD;
                }
                if (f.channel() != null) f.channel().close();
            } catch (Throwable t) {
                e.status = ProxyEntry.Status.DEAD;
            } finally {
                group.shutdownGracefully();
            }
        }, "nomad-proxy-test-" + e.label()).start();
    }

    /** Build the Netty proxy handler for an entry — shared by the test path and the join pipeline. */
    public static ChannelHandler newProxyHandler(ProxyEntry e) {
        InetSocketAddress addr = new InetSocketAddress(e.host, e.port);
        return switch (e.proto) {
            case SOCKS5 -> e.hasAuth() ? new Socks5ProxyHandler(addr, e.username, e.password) : new Socks5ProxyHandler(addr);
            case SOCKS4 -> e.hasAuth() ? new Socks4ProxyHandler(addr, e.username) : new Socks4ProxyHandler(addr);
            case HTTP   -> e.hasAuth() ? new HttpProxyHandler(addr, e.username, e.password) : new HttpProxyHandler(addr);
        };
    }

    // ── Encrypted persistence (nomad/proxies.json) ────────────────────────────

    public void save() {
        try {
            JsonArray arr = new JsonArray();
            for (ProxyEntry e : pool) {
                JsonObject o = new JsonObject();
                o.addProperty("id", e.id);
                if (e.name != null) o.addProperty("name", e.name);
                o.addProperty("proto", e.proto.name());
                o.addProperty("host", e.host);
                o.addProperty("port", e.port);
                if (e.username != null) o.addProperty("username", ProxyCrypto.enc(e.username));
                if (e.password != null) o.addProperty("password", ProxyCrypto.enc(e.password));
                o.addProperty("active", e.active);
                arr.add(o);
            }
            JsonObject root = new JsonObject();
            root.add("entries", arr);
            Path file = poolFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("[Nomad/Proxy] save failed: {}", e.toString());
        }
    }

    public void load() {
        try {
            Path file = poolFile();
            if (!Files.exists(file)) return;
            JsonObject root = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            if (root == null || !root.has("entries")) return;
            pool.clear();
            activeEntry = null;
            for (var el : root.getAsJsonArray("entries")) {
                JsonObject o = el.getAsJsonObject();
                ProxyEntry e = new ProxyEntry();
                if (o.has("id"))   e.id = o.get("id").getAsString();
                if (o.has("name")) e.name = o.get("name").getAsString();
                e.proto = ProxyEntry.Proto.valueOf(o.get("proto").getAsString());
                e.host = o.get("host").getAsString();
                e.port = o.get("port").getAsInt();
                if (o.has("username")) e.username = ProxyCrypto.dec(o.get("username").getAsString());
                if (o.has("password")) e.password = ProxyCrypto.dec(o.get("password").getAsString());
                e.active = o.has("active") && o.get("active").getAsBoolean();
                if (e.active) activeEntry = e;
                pool.add(e);
            }
        } catch (Exception e) {
            LOG.warn("[Nomad/Proxy] load failed: {}", e.toString());
        }
    }
}
