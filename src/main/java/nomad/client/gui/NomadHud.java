package nomad.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import nomad.client.mixin.accessor.ScreenAccessor;
import nomad.client.proxy.ProxyEntry;
import nomad.client.proxy.ProxyManager;

/** Bottom-right "Account" / "Proxy" buttons dropped onto vanilla screens (title, multiplayer
 *  list, ...) so the active account/proxy is always visible without opening the switcher. */
public final class NomadHud {
    public static final int BUTTON_W = 150;
    public static final int BUTTON_H = 20;
    private static final int MARGIN = 6;
    private static final int GAP = 2;

    private NomadHud() {}

    public static void addButtons(Screen screen, int width, int height) {
        int x = width - MARGIN - BUTTON_W;
        int proxyY = height - MARGIN - BUTTON_H;
        int accountY = proxyY - GAP - BUTTON_H;

        Button account = Button.builder(Component.literal(accountLabel()), b -> open(screen))
                .bounds(x, accountY, BUTTON_W, BUTTON_H).build();
        Button proxy = Button.builder(Component.literal(proxyLabel()), b -> open(screen))
                .bounds(x, proxyY, BUTTON_W, BUTTON_H).build();

        ((ScreenAccessor) screen).nomad$addRenderableWidget(account);
        ((ScreenAccessor) screen).nomad$addRenderableWidget(proxy);
    }

    private static void open(Screen current) {
        Minecraft.getInstance().setScreen(new NomadScreen(current));
    }

    private static String accountLabel() {
        String name = Minecraft.getInstance().getUser().getName();
        return "Account: " + name;
    }

    private static String proxyLabel() {
        ProxyEntry e = ProxyManager.INSTANCE.getActiveEntry();
        return "Proxy: " + (e == null ? "Direct" : e.displayName());
    }
}
