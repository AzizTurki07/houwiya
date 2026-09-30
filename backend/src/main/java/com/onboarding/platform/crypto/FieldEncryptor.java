package com.onboarding.platform.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Encrypts personal data before it reaches the database (AES-256-GCM, random 96-bit IV per
 * value, so equal plaintexts never produce equal ciphertexts), and produces keyed hashes
 * (HMAC-SHA256) for the few values that must be searchable, like the document number used to
 * detect duplicate applications.
 *
 * Two independent sub-keys are derived from the configured master key, one per purpose, so
 * the lookup hashes reveal nothing about the encryption key.
 *
 * Stored format: "v1:" + base64(iv || ciphertext || tag). The version prefix leaves room for
 * key rotation later.
 */
@Slf4j
@Component
public class FieldEncryptor {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    // Public dev key from application.yml -- flagged loudly if it is still in use.
    private static final String DEV_KEY = "REVWLU9OTFktS0VZLW5ldmVyLXVzZS1mb3ItcmVhbCE=";

    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec hashKey;
    private final SecureRandom random = new SecureRandom();

    public FieldEncryptor(@Value("${app.crypto.key}") String base64Key) {
        byte[] master;
        try {
            master = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.crypto.key must be base64 (e.g. `openssl rand -base64 32`)", e);
        }
        if (master.length != 32) {
            throw new IllegalStateException("app.crypto.key must decode to exactly 32 bytes, got " + master.length);
        }
        if (DEV_KEY.equals(base64Key.trim())) {
            log.warn("Personal data is encrypted with the PUBLIC development key. Set APP_ENCRYPTION_KEY "
                    + "before storing any real document data.");
        }
        this.encryptionKey = new SecretKeySpec(derive(master, "houwiya/field-encryption/v1"), "AES");
        this.hashKey = new SecretKeySpec(derive(master, "houwiya/lookup-hash/v1"), "HmacSHA256");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        return PREFIX + Base64.getEncoder().encodeToString(encryptBytes(plaintext.getBytes(StandardCharsets.UTF_8)));
    }

    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith(PREFIX)) {
            // Written before encryption existed; returned as-is and re-encrypted on the next save.
            return stored;
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Stored encrypted value is corrupt", e);
        }
        return new String(decryptBytes(data), StandardCharsets.UTF_8);
    }

    /** iv || ciphertext || tag. */
    public byte[] encryptBytes(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public byte[] decryptBytes(byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
            return cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Wrong key or tampered data: GCM authentication fails rather than returning garbage.
            throw new IllegalStateException("Could not decrypt a stored value (wrong APP_ENCRYPTION_KEY?)", e);
        }
    }

    /** Deterministic keyed hash (hex) for equality lookups on an encrypted value. */
    public String lookupHash(String value) {
        if (value == null) {
            return null;
        }
        return HexFormat.of().formatHex(hmac(hashKey, value.trim().toUpperCase().getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] derive(byte[] master, String purpose) {
        return hmac(new SecretKeySpec(master, "HmacSHA256"), purpose.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(SecretKeySpec key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }
}
