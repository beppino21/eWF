package it.eone.ewf.service.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Hash delle password con PBKDF2-HMAC-SHA256 (solo JDK). Formato salvato in app_user.password_hash:
 * {@code pbkdf2-sha256$<iterazioni>$<sale base64>$<hash base64>}. Le iterazioni stanno nell'hash, quindi
 * si possono aumentare in futuro senza invalidare le password esistenti (vedi {@link #needsRehash}).
 */
public final class PasswordHasher {

    public static final int DEFAULT_ITERATIONS = 600_000;   // raccomandazione OWASP per PBKDF2-HMAC-SHA256
    private static final String PREFIX = "pbkdf2-sha256";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private final int iterations;
    private final SecureRandom random = new SecureRandom();

    public PasswordHasher() { this(DEFAULT_ITERATIONS); }

    /** Iterazioni ridotte solo nei test. */
    public PasswordHasher(int iterations) {
        if (iterations < 1000) throw new IllegalArgumentException("Iterazioni troppo poche: " + iterations);
        this.iterations = iterations;
    }

    public String hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] h = pbkdf2(password, salt, iterations);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return PREFIX + "$" + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(h);
    }

    /** false anche per un hash malformato o assente. */
    public boolean verify(char[] password, String stored) {
        Parsed p = parse(stored);
        if (p == null) {
            pbkdf2(password, new byte[SALT_BYTES], iterations);          // stesso tempo di risposta
            return false;
        }
        return MessageDigest.isEqual(p.hash, pbkdf2(password, p.salt, p.iterations));
    }

    public boolean needsRehash(String stored) {
        Parsed p = parse(stored);
        return p == null || p.iterations < iterations;
    }

    private record Parsed(int iterations, byte[] salt, byte[] hash) {}

    private static Parsed parse(String stored) {
        if (stored == null) return null;
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) return null;
        try {
            Base64.Decoder d = Base64.getDecoder();
            return new Parsed(Integer.parseInt(parts[1]), d.decode(parts[2]), d.decode(parts[3]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 non disponibile", e);
        } finally {
            spec.clearPassword();
        }
    }
}
