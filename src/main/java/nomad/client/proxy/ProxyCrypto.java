package nomad.client.proxy;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Hardware-bound AES-256-GCM string encryption for proxy credentials at rest.
 * The key is derived from a stable machine identity, so the encrypted blob is
 * meaningless if copied to another machine and is never synced anywhere.
 */
public final class ProxyCrypto {

    private ProxyCrypto() {}

    /** Encrypt to Base64([12-byte IV | ciphertext | 16-byte GCM tag]); null/empty passes through. */
    public static String enc(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] ct  = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[12 + ct.length];
            System.arraycopy(iv, 0, out, 0, 12);
            System.arraycopy(ct, 0, out, 12, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            return null;
        }
    }

    /** Decrypt a value produced by {@link #enc}; null/empty passes through. */
    public static String dec(String encoded) {
        if (encoded == null || encoded.isEmpty()) return encoded;
        try {
            byte[] data = Base64.getDecoder().decode(encoded);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, data, 0, 12));
            return new String(c.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static SecretKey key() throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(hardwareId().getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(hash, "AES");
    }

    private static String hardwareId() {
        String host = System.getenv("COMPUTERNAME");
        if (host == null || host.isBlank()) host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) host = "unknown";
        return host + "|" + System.getProperty("user.name", "user") + "|nomad-proxy";
    }
}
