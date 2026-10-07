package guide.domain.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The field cipher of the PII vault: AES-256-GCM, a fresh 12-byte IV per
 * value, 128-bit auth tag. Two properties are load-bearing teaching:
 *
 * - AEAD (GCM) gives AUTHENTICATION, not just secrecy — a flipped ciphertext
 *   bit fails the tag instead of decrypting to plausible garbage.
 * - A fresh RANDOM IV per encryption means the same plaintext sealed twice
 *   yields different ciphertext (the demo proves it) — an observer cannot
 *   even tell two subjects share a value.
 *
 * The wire form is base64(iv ‖ ciphertext) so rows stay printable text.
 * Never reused IVs + never derived keys: the key comes from the KeyVault,
 * one per subject.
 */
public final class AesGcmFieldCipher {

    static final int IV_BYTES = 12;
    static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final SecureRandom RANDOM = new SecureRandom();

    private AesGcmFieldCipher() {
    }

    /** Seal one plaintext field under the subject's key. */
    public static String encrypt(SecretKey key, String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] wire = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, wire, 0, iv.length);
            System.arraycopy(ciphertext, 0, wire, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(wire);
        } catch (Exception failed) {
            // AEAD failures are operator/programming errors, never input-shaped
            throw new IllegalStateException("AES-GCM encrypt failed: " + failed.getMessage(), failed);
        }
    }

    /**
     * Open one sealed field. A missing or destroyed key must be checked by the
     * CALLER (that is the erasure story) — reaching here means the key existed;
     * a tag failure then means tampering or corruption.
     */
    public static String decrypt(SecretKey key, String sealed) {
        try {
            byte[] wire = Base64.getDecoder().decode(sealed);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, wire, 0, IV_BYTES));
            byte[] plaintext = cipher.doFinal(wire, IV_BYTES, wire.length - IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception failed) {
            throw new IllegalStateException("AES-GCM decrypt failed (tampered or corrupt ciphertext): "
                    + failed.getMessage(), failed);
        }
    }
}
