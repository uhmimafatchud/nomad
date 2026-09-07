package nomad.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import nomad.client.account.AccountManager;
import nomad.client.proxy.ProxyEntry;
import nomad.client.proxy.ProxyManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Minimal vanilla-widget switcher: two tabs, one for Microsoft/offline accounts, one for proxies. */
public final class NomadScreen extends Screen {
    private static final int MAX_PROXY_ENDPOINT_LENGTH = 512;
    private static final int MAX_PROXY_CREDENTIAL_LENGTH = 2048;

    private enum Tab { ACCOUNTS, PROXIES }

    private final Screen parent;
    private Tab tab = Tab.ACCOUNTS;
    private String status = "";
    private Object pendingDelete;   // Account or ProxyEntry awaiting a second click to confirm removal

    private EditBox offlineNameBox;
    private EditBox proxyHostBox;   // "host:port"
    private EditBox proxyUserBox;   // optional
    private EditBox proxyPassBox;   // optional
    private final Map<ProxyEntry, Button> proxyRowButtons = new HashMap<>();

    public NomadScreen(Screen parent) {
        super(Component.literal("Nomad"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();

        int top = 28;
        addRenderableWidget(Button.builder(Component.literal(tab == Tab.ACCOUNTS ? "[ Accounts ]" : "Accounts"),
                b -> { tab = Tab.ACCOUNTS; status = ""; pendingDelete = null; init(); }).bounds(width / 2 - 104, top, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal(tab == Tab.PROXIES ? "[ Proxies ]" : "Proxies"),
                b -> { tab = Tab.PROXIES; status = ""; pendingDelete = null; init(); }).bounds(width / 2 + 4, top, 100, 20).build());

        if (tab == Tab.ACCOUNTS) initAccounts(top + 30);
        else initProxies(top + 30);
    }

    // ── Accounts tab ─────────────────────────────────────────────────────────

    private void initAccounts(int y) {
        addRenderableWidget(Button.builder(Component.literal("Add Microsoft Account"), b -> startMsLogin())
                .bounds(width / 2 - 104, y, 208, 20).build());
        y += 26;

        offlineNameBox = new EditBox(font, width / 2 - 104, y, 148, 20, Component.literal("offline name"));
        offlineNameBox.setMaxLength(16);
        offlineNameBox.setHint(Component.literal("offline name").withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(offlineNameBox);
        addRenderableWidget(Button.builder(Component.literal("Add"), b -> {
            AccountManager.INSTANCE.addOffline(offlineNameBox.getValue());
            offlineNameBox.setValue("");
            init();
        }).bounds(width / 2 + 48, y, 56, 20).build());
        y += 28;

        List<AccountManager.Account> accounts = AccountManager.INSTANCE.getAccounts();
        int max = Math.min(accounts.size(), 8);
        for (int i = 0; i < max; i++) {
            AccountManager.Account a = accounts.get(i);
            boolean active = AccountManager.INSTANCE.isActive(a);
            String label = (active ? "* " : "") + a.name() + " (" + a.type() + ")";
            int ry = y + i * 24;

            addRenderableWidget(Button.builder(Component.literal(label), b -> {
                AccountManager.INSTANCE.switchTo(a, err -> status = "Switch failed: " + err);
                status = active ? "" : "Switching to " + a.name() + "…";
            }).bounds(width / 2 - 104, ry, 168, 20).build());

            addRenderableWidget(Button.builder(Component.literal(pendingDelete == a ? "Confirm" : "X"), b -> {
                if (pendingDelete == a) {
                    AccountManager.INSTANCE.remove(a);
                    pendingDelete = null;
                    init();
                } else {
                    pendingDelete = a;
                    status = "Click X again to remove " + a.name();
                    init();
                }
            }).bounds(width / 2 + 68, ry, 36, 20).build());
        }
    }

    private void startMsLogin() {
        status = "Starting Microsoft login…";
        AccountManager.INSTANCE.startMicrosoftLogin(
                url -> status = "Browser opened — sign in, then return here.",
                acc -> status = "Signed in as " + acc.name(),
                err -> status = "Login failed: " + err);
    }

    // ── Proxies tab ──────────────────────────────────────────────────────────

    private void initProxies(int y) {
        proxyHostBox = new EditBox(font, width / 2 - 104, y, 208, 20, Component.literal("host:port"));
        proxyHostBox.setMaxLength(MAX_PROXY_ENDPOINT_LENGTH);
        proxyHostBox.setHint(Component.literal("host:port").withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(proxyHostBox);
        y += 24;

        proxyUserBox = new EditBox(font, width / 2 - 104, y, 100, 20, Component.literal("username"));
        proxyUserBox.setMaxLength(MAX_PROXY_CREDENTIAL_LENGTH);
        proxyUserBox.setHint(Component.literal("username (optional)").withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(proxyUserBox);
        proxyPassBox = new EditBox(font, width / 2 + 4, y, 100, 20, Component.literal("password"));
        proxyPassBox.setMaxLength(MAX_PROXY_CREDENTIAL_LENGTH);
        proxyPassBox.setHint(Component.literal("password (optional)").withStyle(ChatFormatting.DARK_GRAY));
        proxyPassBox.setFormatter((s, i) -> Component.literal("*".repeat(s.length())).getVisualOrderText());
        addRenderableWidget(proxyPassBox);
        y += 26;

        addRenderableWidget(Button.builder(Component.literal("Add SOCKS5"), b -> addProxy(ProxyEntry.Proto.SOCKS5))
                .bounds(width / 2 - 104, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Add HTTP"), b -> addProxy(ProxyEntry.Proto.HTTP))
                .bounds(width / 2 + 4, y, 100, 20).build());
        y += 26;

        addRenderableWidget(Button.builder(Component.literal("Direct (no proxy)"), b -> {
            ProxyManager.INSTANCE.setActiveEntry(null);
            init();
        }).bounds(width / 2 - 104, y, 208, 20).build());
        y += 28;

        proxyRowButtons.clear();
        List<ProxyEntry> pool = ProxyManager.INSTANCE.getPool();
        int max = Math.min(pool.size(), 7);
        for (int i = 0; i < max; i++) {
            ProxyEntry e = pool.get(i);
            int ry = y + i * 24;

            Button rowButton = Button.builder(Component.literal(proxyRowLabel(e)), b -> {
                ProxyManager.INSTANCE.setActiveEntry(e);
                init();
            }).bounds(width / 2 - 104, ry, 128, 20).build();
            addRenderableWidget(rowButton);
            proxyRowButtons.put(e, rowButton);

            addRenderableWidget(Button.builder(Component.literal("Test"), b -> {
                status = "Testing " + e.displayName() + "…";
                ProxyManager.INSTANCE.testEntry(e);
            }).bounds(width / 2 + 28, ry, 40, 20).build());

            addRenderableWidget(Button.builder(Component.literal(pendingDelete == e ? "Confirm" : "X"), b -> {
                if (pendingDelete == e) {
                    ProxyManager.INSTANCE.removeEntry(e);
                    pendingDelete = null;
                    init();
                } else {
                    pendingDelete = e;
                    status = "Click X again to remove " + e.displayName();
                    init();
                }
            }).bounds(width / 2 + 72, ry, 32, 20).build());
        }
    }

    /** Row label reflecting live test state — recomputed each tick so the Test button's result shows up
     *  without waiting for a full re-init (which would blow away whatever the user is typing). */
    private String proxyRowLabel(ProxyEntry e) {
        boolean active = e == ProxyManager.INSTANCE.getActiveEntry();
        return (active ? "* " : "") + e.displayName() + " [" + e.proto + "] "
                + (e.status == ProxyEntry.Status.ALIVE ? e.pingMs + "ms"
                   : e.status == ProxyEntry.Status.DEAD ? "dead"
                   : e.status == ProxyEntry.Status.TESTING ? "testing…" : "");
    }

    private void addProxy(ProxyEntry.Proto proto) {
        String raw = proxyHostBox.getValue().trim();
        if (raw.isEmpty()) return;
        String[] parts = raw.split(":");
        if (parts.length != 2) { status = "Format: host:port"; return; }
        try {
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);
            String user = proxyUserBox.getValue().trim();
            String pass = proxyPassBox.getValue().trim();
            ProxyManager.INSTANCE.addEntry(new ProxyEntry(proto, host, port,
                    user.isEmpty() ? null : user, pass.isEmpty() ? null : pass));
            proxyHostBox.setValue("");
            proxyUserBox.setValue("");
            proxyPassBox.setValue("");
            status = "";
            init();
        } catch (NumberFormatException e) {
            status = "Invalid port";
        }
    }

    // ── Render / lifecycle ──────────────────────────────────────────────────

    @Override
    public void tick() {
        super.tick();
        if (tab == Tab.PROXIES)
            proxyRowButtons.forEach((e, btn) -> btn.setMessage(Component.literal(proxyRowLabel(e))));
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        fill(poseStack, 0, 0, width, height, 0xC0101014);
        drawCenteredString(poseStack, font, "Nomad", width / 2, 10, 0xFFFFFFFF);
        super.render(poseStack, mouseX, mouseY, partialTick);
        if (tab == Tab.PROXIES) {
            ProxyEntry active = ProxyManager.INSTANCE.getActiveEntry();
            String activeLine = "Active: " + (active == null ? "Direct (no proxy)"
                    : active.displayName() + " [" + active.proto + "]");
            drawCenteredString(poseStack, font, activeLine, width / 2, height - 34, 0xFF55FFFF);
        }
        if (!status.isEmpty())
            drawCenteredString(poseStack, font, status, width / 2, height - 20, 0xFFFFD966);
    }

    @Override
    public void onClose() {
        AccountManager.INSTANCE.cancelMicrosoftLogin();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
