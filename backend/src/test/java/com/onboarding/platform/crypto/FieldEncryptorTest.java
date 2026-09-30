package com.onboarding.platform.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldEncryptorTest {

    private static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes());

    private final FieldEncryptor encryptor = new FieldEncryptor(KEY);

    @Test
    void roundTrips_includingArabic() {
        String text = "{\"last_name\":\"تركي\",\"document_number\":\"11438922\"}";
        String stored = encryptor.encrypt(text);
        assertThat(stored).startsWith("v1:").doesNotContain("11438922");
        assertThat(encryptor.decrypt(stored)).isEqualTo(text);
    }

    @Test
    void samePlaintext_encryptsDifferentlyEveryTime() {
        assertThat(encryptor.encrypt("P1234567")).isNotEqualTo(encryptor.encrypt("P1234567"));
    }

    @Test
    void tamperedCiphertext_isRejectedNotMisread() {
        String stored = encryptor.encrypt("P1234567");
        byte[] raw = Base64.getDecoder().decode(stored.substring(3));
        raw[raw.length - 1] ^= 1;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> encryptor.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anotherKey_cannotDecrypt() {
        String stored = encryptor.encrypt("P1234567");
        assertThatThrownBy(() -> new FieldEncryptor(OTHER_KEY).decrypt(stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bytesRoundTrip() {
        byte[] photo = {1, 2, 3, 4, 5};
        assertThat(encryptor.decryptBytes(encryptor.encryptBytes(photo))).isEqualTo(photo);
    }

    @Test
    void lookupHash_isStable_normalised_andKeyed() {
        assertThat(encryptor.lookupHash("p1234567 ")).isEqualTo(encryptor.lookupHash("P1234567")).hasSize(64);
        assertThat(encryptor.lookupHash("P1234567")).isNotEqualTo(encryptor.lookupHash("P1234568"));
        assertThat(new FieldEncryptor(OTHER_KEY).lookupHash("P1234567")).isNotEqualTo(encryptor.lookupHash("P1234567"));
    }

    @Test
    void valuesStoredBeforeEncryption_areStillReadable() {
        assertThat(encryptor.decrypt("legacy plaintext")).isEqualTo("legacy plaintext");
        assertThat(encryptor.decrypt(null)).isNull();
    }

    @Test
    void rejectsKeysOfTheWrongSize() {
        assertThatThrownBy(() -> new FieldEncryptor(Base64.getEncoder().encodeToString(new byte[16])))
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new FieldEncryptor("not base64!!")).hasMessageContaining("base64");
    }
}
