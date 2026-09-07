package nomad.client;

import net.fabricmc.api.ClientModInitializer;
import nomad.client.account.AccountManager;
import nomad.client.proxy.ProxyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Nomad implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("Nomad");

    @Override
    public void onInitializeClient() {
        AccountManager.INSTANCE.load();
        ProxyManager.INSTANCE.load();
        LOG.info("Nomad loaded — press N with no screen open to switch accounts/proxies.");
    }
}
