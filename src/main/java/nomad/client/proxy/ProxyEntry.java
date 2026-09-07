package nomad.client.proxy;

import java.util.UUID;

/**
 * A single proxy in the pool. Runtime model — credentials are held in memory in plaintext
 * but encrypted at rest by {@link ProxyCrypto}.
 * <p>
 * Exactly one entry in the pool is {@link #active} at a time (the one routing traffic).
 */
public class ProxyEntry {

    public enum Proto  { SOCKS5, SOCKS4, HTTP }
    public enum Status { UNKNOWN, TESTING, ALIVE, DEAD }

    public String  id = UUID.randomUUID().toString();
    public String  name = "";   // user-facing label (falls back to host:port)
    public Proto   proto  = Proto.SOCKS5;
    public String  host = "";
    public int     port = 1080;
    public String  username;   // nullable
    public String  password;   // nullable

    public volatile Status status = Status.UNKNOWN;
    public volatile int    pingMs = -1;
    public boolean active;

    public ProxyEntry() {}

    public ProxyEntry(Proto proto, String host, int port, String username, String password) {
        this.proto = proto;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    public boolean hasAuth() {
        return username != null && !username.isEmpty();
    }

    /** Endpoint label — host:port. */
    public String label() {
        return host + ":" + port;
    }

    /** Friendly display name (falls back to the endpoint). */
    public String displayName() {
        return name == null || name.isBlank() ? label() : name;
    }
}
