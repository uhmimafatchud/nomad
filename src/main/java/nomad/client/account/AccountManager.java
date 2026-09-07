package nomad.client.account;

import com.google.gson.*;
import com.mojang.authlib.minecraft.UserApiService;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.core.UUIDUtil;
import nomad.client.mixin.accessor.MinecraftClientAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Consumer;

/**
 * Self-contained Microsoft account manager: browser OAuth login, hardware-bound encrypted
 * storage, and hot-swapping the live session on {@link Minecraft} without a restart.
 * No backend of its own — talks directly to Microsoft/Xbox/Mojang's public auth APIs.
 */
public final class AccountManager {
    public static final AccountManager INSTANCE = new AccountManager();

    private static final Logger LOG = LoggerFactory.getLogger("Nomad/Accounts");
    private static final File   ACCOUNTS_FILE = FabricLoader.getInstance().getGameDir()
            .resolve("nomad").resolve("accounts.dat").toFile();

    // Meteor Client's public app registration — works out of the box. For your own release,
    // register an Azure AD app (https://portal.azure.com) with redirect URI matching REDIRECT_URI
    // below and swap CLIENT_ID; a shared third-party app id is fine for personal/testing use only.
    private static final String CLIENT_ID    = "54fd49e4-2103-4044-9603-2b028c814ec3";
    private static final String SCOPE        = "XboxLive.signin%20offline_access";
    private static final String REDIRECT_URI = "http://localhost:9675";
    private static final String AUTH_URL     = "https://login.live.com/oauth20_authorize.srf";
    private static final String TOKEN_URL    = "https://login.live.com/oauth20_token.srf";

    // ── Types ─────────────────────────────────────────────────────────────────

    public enum AccountType { MICROSOFT, OFFLINE }

    public record Account(String name, String uuid, AccountType type, String refreshToken) {
        public Account(String name, String uuid, AccountType type) { this(name, uuid, type, ""); }
        public boolean isOffline()       { return type == AccountType.OFFLINE; }
        public boolean isMicrosoft()     { return type == AccountType.MICROSOFT; }
        public boolean hasRefreshToken() { return refreshToken != null && !refreshToken.isBlank(); }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final List<Account> accounts = new ArrayList<>();
    private Thread           msLoginThread;
    private volatile boolean msLoginCancelled = false;
    private volatile boolean msSwitching = false;

    private AccountManager() {}

    private static Minecraft mc() { return Minecraft.getInstance(); }

    // ── Queries ───────────────────────────────────────────────────────────────

    public List<Account> getAccounts() { return Collections.unmodifiableList(accounts); }

    public boolean isActive(Account a) {
        try {
            return mc().getUser().getProfileId().equals(UUID.fromString(a.uuid()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public boolean isMsLoginInProgress() { return msLoginThread != null && msLoginThread.isAlive(); }
    public boolean isMsSwitching()       { return msSwitching; }

    // ── Mutations ─────────────────────────────────────────────────────────────

    public void addOffline(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return;
        String uuid = UUIDUtil.createOfflinePlayerUUID(trimmed).toString();
        accounts.removeIf(a -> a.name().equalsIgnoreCase(trimmed) && a.isOffline());
        accounts.add(0, new Account(trimmed, uuid, AccountType.OFFLINE));
        save();
    }

    public void remove(Account account) { accounts.remove(account); save(); }

    public void moveToTop(Account account) {
        if (accounts.remove(account)) { accounts.add(0, account); save(); }
    }

    public void switchTo(Account account, Consumer<Account> onSuccess, Consumer<String> onError) {
        if (isActive(account)) { onSuccess.accept(account); return; }
        try {
            UUID uuid = UUID.fromString(account.uuid());
            if (account.isMicrosoft() && account.hasRefreshToken()) {
                if (msSwitching) { onError.accept("another account switch is already in progress"); return; }
                msSwitching = true;
                Thread t = new Thread(() -> {
                    try {
                        String msAccess = refreshMsToken(account.refreshToken());
                        String[] xbl    = xblAuth(msAccess);
                        String[] xsts   = xstsAuth(xbl[0]);
                        String mcToken  = mcAuth(xsts[0], xsts[1]);
                        User u = new User(account.name(), uuid, mcToken, Optional.empty(), Optional.empty());
                        mc().execute(() -> {
                            try {
                                setSession(u);
                                onSuccess.accept(account);
                                LOG.info("Switched to MS account: {}", account.name());
                            } catch (Exception e) {
                                String why = e.getMessage() == null ? "session swap failed" : e.getMessage();
                                LOG.warn("Session swap failed: {}", why);
                                onError.accept(why);
                            } finally {
                                msSwitching = false;
                            }
                        });
                    } catch (Exception e) {
                        String why = e.getMessage() == null ? "Microsoft re-auth failed" : e.getMessage();
                        LOG.warn("MS re-auth failed (session unchanged): {}", why);
                        mc().execute(() -> onError.accept(why));
                        msSwitching = false;
                    }
                }, "nomad-ms-switch");
                t.setDaemon(true);
                t.start();
            } else {
                setSession(new User(account.name(), uuid, "", Optional.empty(), Optional.empty()));
                onSuccess.accept(account);
                LOG.info("Switched to {} ({})", account.name(), account.type());
            }
        } catch (Exception e) {
            String why = e.getMessage() == null ? "invalid account data" : e.getMessage();
            LOG.error("Failed to switch: {}", why);
            onError.accept(why);
        }
    }

    // ── Microsoft Browser Login ───────────────────────────────────────────────

    /**
     * Starts Microsoft login via browser redirect to localhost:9675.
     * Opens the browser, waits for the OAuth callback, then chains XBL → XSTS → MC auth.
     */
    public void startMicrosoftLogin(Consumer<String> onBrowserOpened, Consumer<Account> onSuccess,
                                     Consumer<String> onError) {
        cancelMicrosoftLogin();
        msLoginCancelled = false;

        msLoginThread = new Thread(() -> {
            try {
                String authUrl = AUTH_URL
                        + "?client_id="    + CLIENT_ID
                        + "&response_type=code"
                        + "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8)
                        + "&scope="        + SCOPE
                        + "&prompt=select_account";

                net.minecraft.util.Util.getPlatform().openUri(authUrl);
                mc().execute(() -> onBrowserOpened.accept(authUrl));

                String code;
                try (ServerSocket server = new ServerSocket()) {
                    server.setReuseAddress(true);
                    server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 9675));
                    server.setSoTimeout(120_000);
                    try (Socket sock = server.accept()) {
                        BufferedReader in = new BufferedReader(
                                new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
                        String requestLine = in.readLine();

                        PrintWriter out = new PrintWriter(
                                new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8));
                        String html = "<html><body style='font-family:sans-serif;background:#0d0d10;"
                                + "color:#fff;display:flex;align-items:center;justify-content:center;"
                                + "height:100vh;margin:0'><div style='text-align:center'>"
                                + "<h2>logged in</h2><p>you can close this tab and return to the game</p>"
                                + "</div></body></html>";
                        out.print("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nConnection: close\r\n\r\n" + html);
                        out.flush();

                        code = null;
                        if (requestLine != null) {
                            int q = requestLine.indexOf('?');
                            int sp = requestLine.lastIndexOf(' ');
                            if (q >= 0 && sp > q) {
                                for (String p : requestLine.substring(q + 1, sp).split("&")) {
                                    if (p.startsWith("code=")) { code = p.substring(5); break; }
                                }
                            }
                        }
                    }
                }

                if (msLoginCancelled) return;
                if (code == null) throw new Exception("No authorisation code in callback");

                String tokenBody = "grant_type=authorization_code"
                        + "&client_id="    + CLIENT_ID
                        + "&code="         + code
                        + "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8);
                JsonObject tr = postForm(TOKEN_URL, tokenBody);
                if (tr.has("error")) throw new Exception(tr.has("error_description")
                        ? tr.get("error_description").getAsString() : tr.get("error").getAsString());

                String msAccess  = req(tr, "access_token");
                String msRefresh = tr.has("refresh_token") ? tr.get("refresh_token").getAsString() : "";

                String[] xbl   = xblAuth(msAccess);
                String[] xsts  = xstsAuth(xbl[0]);
                String mcToken = mcAuth(xsts[0], xsts[1]);

                String[] profile = mcProfile(mcToken);
                String name = profile[0], uuidStr = profile[1];

                Account acc = new Account(name, uuidStr, AccountType.MICROSOFT, msRefresh);
                User newUser = new User(name, UUID.fromString(uuidStr), mcToken, Optional.empty(), Optional.empty());
                mc().execute(() -> {
                    accounts.removeIf(a -> a.uuid().equals(uuidStr) && a.isMicrosoft());
                    accounts.add(0, acc);
                    save();
                    setSession(newUser);
                });
                onSuccess.accept(acc);
                LOG.info("Added MS account: {}", name);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (!msLoginCancelled)
                    onError.accept(e.getMessage() != null ? e.getMessage() : "unknown error");
                LOG.error("MS login failed: {}", e.getMessage());
            }
        }, "nomad-ms-login");
        msLoginThread.setDaemon(true);
        msLoginThread.start();
    }

    public void cancelMicrosoftLogin() {
        msLoginCancelled = true;
        if (msLoginThread != null) { msLoginThread.interrupt(); msLoginThread = null; }
    }

    // ── MS Auth HTTP Helpers ──────────────────────────────────────────────────

    private String refreshMsToken(String refreshToken) throws Exception {
        String body = "client_id="  + CLIENT_ID
                + "&refresh_token=" + refreshToken
                + "&grant_type=refresh_token"
                + "&scope="         + SCOPE;
        JsonObject r = postForm(TOKEN_URL, body);
        if (r.has("error")) throw new Exception(r.get("error").getAsString());
        return req(r, "access_token");
    }

    /** Returns [xblToken, uhs]. */
    private String[] xblAuth(String msAccessToken) throws Exception {
        JsonObject props = new JsonObject();
        props.addProperty("AuthMethod", "RPS");
        props.addProperty("SiteName", "user.auth.xboxlive.com");
        props.addProperty("RpsTicket", "d=" + msAccessToken);
        JsonObject body = new JsonObject();
        body.add("Properties", props);
        body.addProperty("RelyingParty", "http://auth.xboxlive.com");
        body.addProperty("TokenType", "JWT");
        JsonObject r = postJson("https://user.auth.xboxlive.com/user/authenticate", body.toString());
        return new String[]{ req(r, "Token"), xui(r, "xblAuth") };
    }

    /** Returns [xstsToken, uhs]. */
    private String[] xstsAuth(String xblToken) throws Exception {
        JsonObject props = new JsonObject();
        props.addProperty("SandboxId", "RETAIL");
        JsonArray tokens = new JsonArray();
        tokens.add(xblToken);
        props.add("UserTokens", tokens);
        JsonObject body = new JsonObject();
        body.add("Properties", props);
        body.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        body.addProperty("TokenType", "JWT");
        JsonObject r = postJson("https://xsts.auth.xboxlive.com/xsts/authorize", body.toString());
        if (r.has("XErr")) {
            long x = r.get("XErr").getAsLong();
            if (x == 2148916233L) throw new Exception("Microsoft account has no Xbox profile");
            if (x == 2148916235L) throw new Exception("Xbox Live not available in your region");
            if (x == 2148916238L) throw new Exception("Child account — parental consent required");
            throw new Exception("XSTS error: " + x);
        }
        return new String[]{ req(r, "Token"), xui(r, "xstsAuth") };
    }

    private String mcAuth(String xstsToken, String uhs) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);
        HttpURLConnection c = open("https://api.minecraftservices.com/authentication/login_with_xbox", "POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        String resp = readResponse(c);
        if (code != 200) {
            String hint = code == 429 ? " — rate-limited by Microsoft; wait a few minutes and retry"
                        : (code == 401 || code == 403) ? " — Xbox auth rejected" : "";
            throw new Exception("Minecraft auth HTTP " + code + hint + ": " + cap(resp));
        }
        JsonObject r = JsonParser.parseString(resp).getAsJsonObject();
        if (!r.has("access_token")) throw new Exception("Minecraft auth: no access_token — " + cap(resp));
        return r.get("access_token").getAsString();
    }

    private static String cap(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 160 ? t.substring(0, 160) : (t.isEmpty() ? "empty body" : t);
    }

    /** Returns [name, uuid]. */
    private String[] mcProfile(String mcToken) throws Exception {
        HttpURLConnection c = open("https://api.minecraftservices.com/minecraft/profile", "GET");
        c.setRequestProperty("Authorization", "Bearer " + mcToken);
        int code = c.getResponseCode();
        String body = readResponse(c);
        if (code != 200) throw new Exception("Profile request failed (" + code + ")");
        JsonObject r = JsonParser.parseString(body).getAsJsonObject();
        if (!r.has("name")) throw new Exception("This account does not own Minecraft");
        String raw  = r.get("id").getAsString();
        String uuid = raw.replaceFirst(
                "([0-9a-f]{8})([0-9a-f]{4})([0-9a-f]{4})([0-9a-f]{4})([0-9a-f]{12})", "$1-$2-$3-$4-$5");
        return new String[]{ r.get("name").getAsString(), uuid };
    }

    // ── HTTP utils ────────────────────────────────────────────────────────────

    private JsonObject postForm(String url, String body) throws Exception {
        HttpURLConnection c = open(url, "POST");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setDoOutput(true);
        c.setReadTimeout(30_000);
        try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
        return JsonParser.parseString(readResponse(c)).getAsJsonObject();
    }

    private JsonObject postJson(String url, String body) throws Exception {
        HttpURLConnection c = open(url, "POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
        return JsonParser.parseString(readResponse(c)).getAsJsonObject();
    }

    private HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        return c;
    }

    private String readResponse(HttpURLConnection c) throws Exception {
        InputStream is;
        try { is = c.getInputStream(); } catch (Exception e) { is = c.getErrorStream(); }
        if (is == null) return "{}";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private static String xui(JsonObject obj, String ctx) throws Exception {
        JsonElement claims = obj.get("DisplayClaims");
        if (claims == null) throw new Exception("Missing DisplayClaims in " + ctx);
        JsonElement xui = claims.getAsJsonObject().get("xui");
        if (xui == null || !xui.isJsonArray() || xui.getAsJsonArray().isEmpty())
            throw new Exception("Missing xui in " + ctx);
        return req(xui.getAsJsonArray().get(0).getAsJsonObject(), "uhs");
    }

    private static String req(JsonObject obj, String field) throws Exception {
        JsonElement el = obj.get(field);
        if (el == null) {
            String s = obj.toString();
            throw new Exception("Missing '" + field + "' in: " + (s.length() > 200 ? s.substring(0, 200) + "…" : s));
        }
        return el.getAsString();
    }

    // ── Session wiring ────────────────────────────────────────────────────────

    private static void setSession(User user) {
        MinecraftClientAccessor mca = (MinecraftClientAccessor) (Object) mc();
        mca.nomad$setSession(user);
        UserApiService api = UserApiService.OFFLINE;
        mca.nomad$setUserApiService(api);
        mca.nomad$setSocialInteractionsManager(new PlayerSocialManager(mc(), api));
        mca.nomad$setProfileKeys(ProfileKeyPairManager.create(api, user, mc().gameDirectory.toPath()));
        mca.nomad$setReportingContext(ReportingContext.create(ReportEnvironment.local(), api));
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    public void load() {
        if (!ACCOUNTS_FILE.exists()) return;
        try {
            SecretKey key = deriveKey(hardwareId());
            byte[] raw = Base64.getDecoder().decode(
                    new String(Files.readAllBytes(ACCOUNTS_FILE.toPath()), StandardCharsets.UTF_8).trim());
            String json = decrypt(raw, key);
            accounts.clear();
            for (JsonElement el : JsonParser.parseString(json).getAsJsonArray()) {
                JsonObject o     = el.getAsJsonObject();
                String name      = o.get("name").getAsString();
                String uuid      = o.get("uuid").getAsString();
                AccountType type = AccountType.valueOf(o.get("type").getAsString());
                String refresh   = o.has("refreshToken") ? o.get("refreshToken").getAsString() : "";
                accounts.add(new Account(name, uuid, type, refresh));
            }
            LOG.info("Loaded {} accounts", accounts.size());
        } catch (Exception e) {
            LOG.error("Failed to load accounts: {}", e.getMessage());
        }
    }

    private void save() {
        try {
            ACCOUNTS_FILE.getParentFile().mkdirs();
            JsonArray arr = new JsonArray();
            for (Account a : accounts) {
                JsonObject o = new JsonObject();
                o.addProperty("name", a.name());
                o.addProperty("uuid", a.uuid());
                o.addProperty("type", a.type().name());
                if (a.hasRefreshToken()) o.addProperty("refreshToken", a.refreshToken());
                arr.add(o);
            }
            String json   = new GsonBuilder().create().toJson(arr);
            SecretKey key = deriveKey(hardwareId());
            String enc    = Base64.getEncoder().encodeToString(encrypt(json, key));
            Files.write(ACCOUNTS_FILE.toPath(), enc.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOG.error("Failed to save accounts: {}", e.getMessage());
        }
    }

    // ── Hardware-bound encryption ─────────────────────────────────────────────

    /** Stable machine identifier: Windows MachineGuid → Linux machine-id → macOS IOPlatformUUID → hostname. */
    private static String hardwareId() {
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid"});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.contains("MachineGuid")) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 3) return parts[parts.length - 1].trim();
                    }
                }
            }
        } catch (Exception ignored) {}
        try {
            File mid = new File("/etc/machine-id");
            if (mid.exists()) {
                try (BufferedReader r = new BufferedReader(new FileReader(mid))) {
                    String id = r.readLine();
                    if (id != null && !id.isBlank()) return id.trim();
                }
            }
        } catch (Exception ignored) {}
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"ioreg", "-rd1", "-c", "IOPlatformExpertDevice"});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.contains("IOPlatformUUID")) {
                        int s = line.indexOf('"', line.indexOf('=') + 1) + 1;
                        int e = line.lastIndexOf('"');
                        if (s > 0 && e > s) return line.substring(s, e).trim();
                    }
                }
            }
        } catch (Exception ignored) {}
        String host = System.getenv("COMPUTERNAME");
        if (host == null || host.isBlank()) host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) host = "unknown";
        return host + "|" + System.getProperty("user.name", "user");
    }

    private static SecretKey deriveKey(String hwId) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(hwId.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(hash, "AES");
    }

    /** AES-256-GCM encrypt. Returns [12-byte IV | ciphertext | 16-byte GCM tag]. */
    private static byte[] encrypt(String plaintext, SecretKey key) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] ct  = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[12 + ct.length];
        System.arraycopy(iv, 0, out, 0, 12);
        System.arraycopy(ct, 0, out, 12, ct.length);
        return out;
    }

    /** AES-256-GCM decrypt. Input must be [12-byte IV | ciphertext | 16-byte GCM tag]. */
    private static String decrypt(byte[] data, SecretKey key) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, data, 0, 12));
        return new String(c.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
    }
}
